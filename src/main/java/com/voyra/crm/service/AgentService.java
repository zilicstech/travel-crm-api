package com.voyra.crm.service;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.dto.AgentCreateRequest;
import com.voyra.crm.dto.AgentCreateResponse;
import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.AgentUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CustomerInteractionRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.IdGenerator;
import com.voyra.crm.util.RandomPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Agency Owner management of their own Agents. Every method is tenant-scoped to the caller's agency. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);
    private static final Set<LeadStatus> PROPOSAL_STAGE_OR_LATER =
            Set.of(LeadStatus.PROPOSAL_SENT, LeadStatus.NEGOTIATING, LeadStatus.BOOKED, LeadStatus.LOST);

    private final AgentRepository agentRepository;
    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final CustomerInteractionRepository customerInteractionRepository;
    private final VisaRepository visaRepository;
    private final AesPasswordEncoder passwordEncoder;

    @Transactional
    public AgentCreateResponse createAgent(AgentCreateRequest request) {
        String tenantId = ownerTenantId();
        if (agentRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalArgumentException("An agent with this email already exists");
        }

        String rawPassword = RandomPasswordGenerator.generate();
        Agent agent = Agent.builder()
                .id(generateUniqueAgentId())
                .tenantId(tenantId)
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .department(request.getDepartment())
                .password(passwordEncoder.encode(rawPassword))
                .isActive(true)
                .build();

        agentRepository.save(agent);
        AgentCache.put(agent.getId(), true);

        log.info("Agent created: agentId={}, tenantId={}", agent.getId(), tenantId);
        return AgentCreateResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .email(agent.getEmail())
                .phone(agent.getPhone())
                .department(agent.getDepartment())
                .initialPassword(rawPassword)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AgentPerformanceResponse> listAgents() {
        String tenantId = ownerTenantId();
        return agentRepository.findByTenantId(tenantId).stream()
                .map(this::toPerformanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentPerformanceResponse getAgent(String id) {
        return toPerformanceResponse(findOwnedAgent(id));
    }

    @Transactional
    public AgentPerformanceResponse updateAgent(String id, AgentUpdateRequest request) {
        Agent agent = findOwnedAgent(id);
        boolean nameChanged = request.getName() != null && !request.getName().equals(agent.getName());

        if (request.getName() != null) {
            agent.setName(request.getName());
        }
        if (request.getPhone() != null) {
            agent.setPhone(request.getPhone());
        }
        if (request.getDepartment() != null) {
            agent.setDepartment(request.getDepartment());
        }
        if (request.getCommissionRate() != null) {
            agent.setCommissionRate(request.getCommissionRate());
        }
        agentRepository.save(agent);

        // Live-sync denormalized name snapshots in the same transaction as the rename.
        if (nameChanged) {
            leadRepository.updateAssignedAgentNameForAgent(agent.getId(), agent.getName());
            bookingRepository.updateAgentNameForAgent(agent.getId(), agent.getName());
            leadNoteRepository.updateAuthorNameForAgent(agent.getId(), agent.getName());
            customerRepository.updateAgentNameForAgent(agent.getId(), agent.getName());
            customerInteractionRepository.updateAuthorNameForAgent(agent.getId(), agent.getName());
            visaRepository.updateAgentNameForAgent(agent.getId(), agent.getName());
        }

        log.info("Agent updated: agentId={}", id);
        return toPerformanceResponse(agent);
    }

    @Transactional
    public AgentPerformanceResponse updateStatus(String id, boolean isActive) {
        Agent agent = findOwnedAgent(id);
        agent.setIsActive(isActive);
        agentRepository.save(agent);
        AgentCache.put(agent.getId(), isActive);
        log.info("Agent status updated: agentId={}, isActive={}", id, isActive);
        return toPerformanceResponse(agent);
    }

    @Transactional
    public void removeAgent(String id) {
        Agent agent = findOwnedAgent(id);
        if (leadRepository.existsByAssignedToAndStatusNotIn(id, TERMINAL_STATUSES)) {
            throw new IllegalStateException(
                    "Agent has active leads assigned - reassign or close them before removing this agent");
        }
        agentRepository.delete(agent);
        AgentCache.remove(id);
        log.info("Agent removed: agentId={}", id);
    }

    @Transactional(readOnly = true)
    public CredentialsResponse getCredentials(String id) {
        Agent agent = findOwnedAgent(id);
        log.info("Agent credentials retrieved: agentId={}, by={}", id, SecurityContextUtil.getAuditInfo());
        return CredentialsResponse.builder()
                .id(agent.getId())
                .email(agent.getEmail())
                .password(passwordEncoder.decode(agent.getPassword()))
                .build();
    }

    private AgentPerformanceResponse toPerformanceResponse(Agent agent) {
        String id = agent.getId();
        long leadsAssigned = leadRepository.countByAssignedTo(id);
        long bookedLeads = leadRepository.countByAssignedToAndStatus(id, LeadStatus.BOOKED);
        long activeLeads = leadRepository.countByAssignedToAndStatusNotIn(id, TERMINAL_STATUSES);
        long quotationsSent = leadRepository.countByAssignedToAndStatusIn(id, PROPOSAL_STAGE_OR_LATER);
        long pendingFollowUps = leadRepository.countByAssignedToAndFollowUpDateLessThanEqualAndStatusNotIn(
                id, LocalDate.now(), TERMINAL_STATUSES);
        long notesLogged = leadNoteRepository.countByAuthorAgentId(id);
        long bookingsCount = bookingRepository.countByAgentId(id);

        var revenue = bookingRepository.sumRevenueByAgentId(id);
        BigDecimal totalRevenue = revenue.getTotalRevenue() != null ? revenue.getTotalRevenue() : BigDecimal.ZERO;
        BigDecimal totalProfit = revenue.getTotalProfit() != null ? revenue.getTotalProfit() : BigDecimal.ZERO;
        BigDecimal commission = totalProfit
                .multiply(agent.getCommissionRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        int conversionPercent = leadsAssigned > 0
                ? BigDecimal.valueOf(bookedLeads * 100.0 / leadsAssigned).setScale(0, RoundingMode.HALF_UP).intValue()
                : 0;

        return AgentPerformanceResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .email(agent.getEmail())
                .phone(agent.getPhone())
                .department(agent.getDepartment())
                .isActive(agent.getIsActive())
                .leadsAssigned(leadsAssigned)
                .activeLeads(activeLeads)
                .bookedLeads(bookedLeads)
                .quotationsSent(quotationsSent)
                .notesLogged(notesLogged)
                .pendingFollowUps(pendingFollowUps)
                .bookingsCount(bookingsCount)
                .revenueGenerated(totalRevenue)
                .profitGenerated(totalProfit)
                .commission(commission)
                .commissionRate(agent.getCommissionRate())
                .conversionPercent(conversionPercent)
                .build();
    }

    private Agent findOwnedAgent(String id) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));
        if (!agent.getTenantId().equals(ownerTenantId())) {
            throw new AccessDeniedException("Agent does not belong to your agency");
        }
        return agent;
    }

    private String ownerTenantId() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.tenantId() == null) {
            throw new AccessDeniedException("No tenant context for current user");
        }
        return principal.tenantId();
    }

    private String generateUniqueAgentId() {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String id = IdGenerator.generate6();
            if (!agentRepository.existsById(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to generate unique agent id after " + maxAttempts + " attempts");
    }
}
