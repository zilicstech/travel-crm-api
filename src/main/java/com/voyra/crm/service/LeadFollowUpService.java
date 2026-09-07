package com.voyra.crm.service;

import com.voyra.crm.dto.FollowUpCreateRequest;
import com.voyra.crm.dto.FollowUpResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.LeadAccessChecker;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A promise to chase something on a lead, scoped to a service TYPE (or trip-level). Every
 * insert/complete/delete recomputes {@code lead.open_follow_ups} and {@code lead.follow_up_date}
 * (the earliest OPEN due date) in the same transaction - per docs/LLD_LEAD_MANAGEMENT.md, that
 * field stops being directly writable once this exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadFollowUpService {

    private final LeadFollowUpRepository leadFollowUpRepository;
    private final LeadRepository leadRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final AgentRepository agentRepository;
    private final LeadTimelineService leadTimelineService;

    @Transactional
    public FollowUpResponse addFollowUp(String leadId, FollowUpCreateRequest request) {
        Lead lead = findAccessibleLeadForCreate(leadId);
        Agent assignee = agentRepository.findById(request.getAssignedAgentId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + request.getAssignedAgentId()));

        LeadFollowUp followUp = LeadFollowUp.builder()
                .id(UniqueIdResolver.resolve(leadFollowUpRepository::existsById))
                .leadId(leadId)
                .clientName(lead.getClientName())
                .leadDestination(lead.getDestination())
                .serviceType(request.getServiceType())
                .dueDate(request.getDueDate())
                .note(request.getNote())
                .assignedAgentId(assignee.getId())
                .assignedAgentName(assignee.getName())
                .status(FollowUpStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        leadFollowUpRepository.save(followUp);

        recomputeFollowUpRollup(lead);
        leadTimelineService.record(leadId, LeadTimelineEventType.FOLLOW_UP_SET,
                "Follow-up set for " + request.getDueDate()
                        + (request.getServiceType() != null ? " (" + request.getServiceType() + ")" : ""));
        log.info("Follow-up added: leadId={}, followUpId={}", leadId, followUp.getId());
        return toResponse(followUp);
    }

    @Transactional
    public FollowUpResponse completeFollowUp(String leadId, String followUpId) {
        LeadFollowUp followUp = findOnLead(leadId, followUpId);
        Lead lead = findAccessibleLead(leadId, followUp.getAssignedAgentId());
        followUp.setStatus(FollowUpStatus.DONE);
        followUp.setCompletedAt(LocalDateTime.now());
        leadFollowUpRepository.save(followUp);

        recomputeFollowUpRollup(lead);
        leadTimelineService.record(leadId, LeadTimelineEventType.FOLLOW_UP_SET,
                "Follow-up completed: " + followUp.getNote());
        log.info("Follow-up completed: leadId={}, followUpId={}", leadId, followUpId);
        return toResponse(followUp);
    }

    @Transactional
    public void deleteFollowUp(String leadId, String followUpId) {
        LeadFollowUp followUp = findOnLead(leadId, followUpId);
        Lead lead = findAccessibleLead(leadId, followUp.getAssignedAgentId());
        leadFollowUpRepository.delete(followUp);

        recomputeFollowUpRollup(lead);
        leadTimelineService.record(leadId, LeadTimelineEventType.FOLLOW_UP_SET,
                "Follow-up removed: " + followUp.getNote());
        log.info("Follow-up removed: leadId={}, followUpId={}", leadId, followUpId);
    }

    @Transactional(readOnly = true)
    public List<FollowUpResponse> listForLead(String leadId) {
        findAccessibleLeadForCreate(leadId);
        return leadFollowUpRepository.findByLeadIdOrderByDueDateAsc(leadId).stream()
                .map(this::toResponse).toList();
    }

    /** GET /api/follow-ups - an agent's own board; the owner sees everyone's unless agentId narrows it. */
    @Transactional(readOnly = true)
    public List<FollowUpResponse> board(String agentIdFilter, FollowUpStatus statusFilter, LocalDate dueBefore) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        String agentId = principal.isAgent() ? principal.userId() : agentIdFilter;

        List<LeadFollowUp> rows;
        if (agentId == null) {
            rows = leadFollowUpRepository.findAll();
        } else if (dueBefore != null) {
            rows = leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc(
                    agentId, statusFilter != null ? statusFilter : FollowUpStatus.OPEN, dueBefore);
        } else if (statusFilter != null) {
            rows = leadFollowUpRepository.findByAssignedAgentIdAndStatusOrderByDueDateAsc(agentId, statusFilter);
        } else {
            rows = leadFollowUpRepository.findByAssignedAgentIdOrderByDueDateAsc(agentId);
        }
        return rows.stream().map(this::toResponse).toList();
    }

    // ---------------------------------------------------------------------

    /**
     * Owner, the lead's creator, or an agent who holds/manages a service on the lead - the
     * same rule {@link LeadAccessChecker#hasServiceAccess} enforces everywhere else, so an
     * agent who can already open the lead via a managed service type is not blocked from its
     * follow-ups. Used for creating a new follow-up, where the caller picks who it is
     * assigned to - that choice must never itself grant access (see {@link #findAccessibleLead}
     * for why the sibling method exists).
     */
    private Lead findAccessibleLeadForCreate(String leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgent()) {
            return lead;
        }
        if (lead.getCreatedBy().equals(principal.userId())) {
            return lead;
        }
        if (LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, principal.userId())) {
            return lead;
        }
        throw new AccessDeniedException("This lead is not accessible to you");
    }

    /**
     * Same as {@link #findAccessibleLeadForCreate}, plus one more case for acting on a
     * follow-up that already exists: the agent it is already assigned to may complete or
     * delete it even if their access to the lead has since changed (e.g. an owner edited
     * their manageableServices after the follow-up was made) - they made the promise, they
     * get to close it out. This is safe only because {@code assigneeId} here is read back
     * from the stored row via {@link #findOnLead}, never chosen fresh by the caller in the
     * same call - {@link #addFollowUp} must never reuse this bypass for that reason, or any
     * agent could plant a follow-up on any lead in the tenant just by naming themselves.
     */
    private Lead findAccessibleLead(String leadId, String assigneeId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgent()) {
            return lead;
        }
        if (lead.getCreatedBy().equals(principal.userId())) {
            return lead;
        }
        if (assigneeId != null && assigneeId.equals(principal.userId())) {
            return lead;
        }
        if (LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, principal.userId())) {
            return lead;
        }
        throw new AccessDeniedException("This lead is not accessible to you");
    }

    private LeadFollowUp findOnLead(String leadId, String followUpId) {
        return leadFollowUpRepository.findByIdAndLeadId(followUpId, leadId)
                .orElseThrow(() -> new IllegalArgumentException("Follow-up not found: " + followUpId));
    }

    private void recomputeFollowUpRollup(Lead lead) {
        long openCount = leadFollowUpRepository.countByLeadIdAndStatus(lead.getId(), FollowUpStatus.OPEN);
        LocalDate earliest = leadFollowUpRepository
                .findFirstByLeadIdAndStatusOrderByDueDateAsc(lead.getId(), FollowUpStatus.OPEN)
                .map(LeadFollowUp::getDueDate).orElse(null);
        lead.setOpenFollowUps((int) openCount);
        lead.setFollowUpDate(earliest);
        leadRepository.save(lead);
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private FollowUpResponse toResponse(LeadFollowUp f) {
        return FollowUpResponse.builder()
                .id(f.getId()).leadId(f.getLeadId()).clientName(f.getClientName())
                .leadDestination(f.getLeadDestination()).serviceType(f.getServiceType())
                .dueDate(f.getDueDate()).note(f.getNote())
                .assignedAgentId(f.getAssignedAgentId()).assignedAgentName(f.getAssignedAgentName())
                .status(f.getStatus()).completedAt(f.getCompletedAt()).createdAt(f.getCreatedAt())
                .build();
    }
}
