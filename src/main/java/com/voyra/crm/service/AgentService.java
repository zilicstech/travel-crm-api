package com.voyra.crm.service;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.dto.AgentCreateRequest;
import com.voyra.crm.dto.AgentCreateResponse;
import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.AgentUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.models.AgentStats;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.RandomPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ClientRepository clientRepository;
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
        List<Agent> agents = agentRepository.findByTenantId(tenantId);
        Map<String, AgentStats> stats = loadStats(agents.stream().map(Agent::getId).toList());
        return agents.stream()
                .map(a -> toPerformanceResponse(a, stats.getOrDefault(a.getId(), AgentStats.empty())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<AgentPerformanceResponse> listAgents(Pageable pageable) {
        String tenantId = ownerTenantId();
        Page<Agent> page = agentRepository.findByTenantId(tenantId, pageable);
        Map<String, AgentStats> stats = loadStats(page.getContent().stream().map(Agent::getId).toList());
        return PagedResponse.from(page, a -> toPerformanceResponse(a, stats.getOrDefault(a.getId(), AgentStats.empty())));
    }

    @Transactional(readOnly = true)
    public AgentPerformanceResponse getAgent(String id) {
        Agent agent = findOwnedAgent(id);
        return toPerformanceResponse(agent, loadStats(List.of(agent.getId()))
                .getOrDefault(agent.getId(), AgentStats.empty()));
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
            clientRepository.updateAgentNameForAgent(agent.getId(), agent.getName());
            visaRepository.updateAgentNameForAgent(agent.getId(), agent.getName());
        }

        log.info("Agent updated: agentId={}", id);
        return toPerformanceResponse(agent, loadStats(List.of(agent.getId()))
                .getOrDefault(agent.getId(), AgentStats.empty()));
    }

    @Transactional
    public AgentPerformanceResponse updateStatus(String id, boolean isActive) {
        Agent agent = findOwnedAgent(id);
        agent.setIsActive(isActive);
        agentRepository.save(agent);
        AgentCache.put(agent.getId(), isActive);
        log.info("Agent status updated: agentId={}, isActive={}", id, isActive);
        return toPerformanceResponse(agent, loadStats(List.of(agent.getId()))
                .getOrDefault(agent.getId(), AgentStats.empty()));
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

    /**
     * Loads metrics for every supplied agent in three grouped queries, replacing the
     * previous eight-queries-per-agent loop. Agents with no rows in a table are absent
     * from that projection, so every lookup falls back to zero.
     */
    private Map<String, AgentStats> loadStats(List<String> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, LeadRepository.AgentLeadStatsProjection> leads =
                leadRepository.aggregateLeadStatsByAgent(agentIds, LeadStatus.BOOKED, TERMINAL_STATUSES,
                                PROPOSAL_STAGE_OR_LATER, LocalDate.now())
                        .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p));
        Map<String, BookingRepository.AgentBookingStatsProjection> bookings =
                bookingRepository.aggregateBookingStatsByAgent(agentIds)
                        .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p));
        Map<String, Long> notes = leadNoteRepository.aggregateNoteCountsByAgent(agentIds)
                .stream().collect(Collectors.toMap(p -> p.getAgentId(), p -> p.getNoteCount()));

        Map<String, AgentStats> result = new HashMap<>();
        for (String id : agentIds) {
            var l = leads.get(id);
            var b = bookings.get(id);
            result.put(id, new AgentStats(
                    l != null ? l.getLeadsAssigned() : 0,
                    l != null ? l.getBookedLeads() : 0,
                    l != null ? l.getActiveLeads() : 0,
                    l != null ? l.getQuotationsSent() : 0,
                    l != null ? l.getPendingFollowUps() : 0,
                    notes.getOrDefault(id, 0L),
                    b != null ? b.getBookingsCount() : 0,
                    b != null && b.getTotalRevenue() != null ? b.getTotalRevenue() : BigDecimal.ZERO,
                    b != null && b.getTotalProfit() != null ? b.getTotalProfit() : BigDecimal.ZERO));
        }
        return result;
    }

    private AgentPerformanceResponse toPerformanceResponse(Agent agent, AgentStats stats) {
        BigDecimal commission = stats.totalProfit()
                .multiply(agent.getCommissionRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        int conversionPercent = stats.leadsAssigned() > 0
                ? BigDecimal.valueOf(stats.bookedLeads() * 100.0 / stats.leadsAssigned())
                        .setScale(0, RoundingMode.HALF_UP).intValue()
                : 0;

        return AgentPerformanceResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .email(agent.getEmail())
                .phone(agent.getPhone())
                .department(agent.getDepartment())
                .isActive(agent.getIsActive())
                .leadsAssigned(stats.leadsAssigned())
                .activeLeads(stats.activeLeads())
                .bookedLeads(stats.bookedLeads())
                .quotationsSent(stats.quotationsSent())
                .notesLogged(stats.notesLogged())
                .pendingFollowUps(stats.pendingFollowUps())
                .bookingsCount(stats.bookingsCount())
                .revenueGenerated(stats.totalRevenue())
                .profitGenerated(stats.totalProfit())
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
        return UniqueIdResolver.resolve(agentRepository::existsById);
    }
}
