package com.voyra.crm.service;

import com.voyra.crm.dto.PublicProposalItemResponse;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.LeadTimelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tenant-scoped work for the unauthenticated public proposal endpoints. Runs in a fresh
 * REQUIRES_NEW transaction acquired AFTER {@link PublicProposalService} sets
 * {@link com.voyra.crm.context.TenantContext} (blueprint §3.5) - must live on its own bean,
 * never called via self-invocation. The DTO is built HERE, inside the correctly-scoped
 * transaction, so a pricing/cost field can never leak by being serialized from a raw entity
 * later, outside this method's control.
 */
@Service
@RequiredArgsConstructor
public class PublicProposalTenantService {

    private static final Set<LeadStatus> ALREADY_PAST_NEGOTIATING =
            Set.of(LeadStatus.NEGOTIATING, LeadStatus.BOOKED, LeadStatus.LOST);

    private final LeadRepository leadRepository;
    private final LeadProposalRepository leadProposalRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadTimelineService leadTimelineService;
    private final LeadTimelineRepository leadTimelineRepository;

    /** Events that change what the customer approved - a later one of these reopens approval. */
    private static final Set<LeadTimelineEventType> PROPOSAL_CHANGES = Set.of(
            LeadTimelineEventType.PROPOSAL_UNLOCKED, LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
            LeadTimelineEventType.PROPOSAL_ITEM_REMOVED);

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PublicProposalResponse> fetchProposal(String leadId) {
        return leadRepository.findById(leadId).map(this::buildResponse);
    }

    /**
     * Every id must belong to this lead and be an option line, or the whole request is
     * rejected - a customer choosing must not be able to select a trip-level add-on into
     * "chosen" state, or reference another lead's line. Idempotent, like approve: picking
     * the same option twice is a no-op success, not an error.
     *
     * <p>A locked proposal rejects the whole request with {@link IllegalStateException} - the
     * customer has no way to unlock it themselves, only an agent/owner can via the internal
     * lead-detail lock control. On success, the proposal auto-locks: the customer's picks are
     * now a commitment, and an agent must consciously reopen it to change anything further.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean selectOptions(String leadId, List<String> selectedItemIds) {
        Optional<Lead> leadOpt = leadRepository.findById(leadId);
        if (leadOpt.isEmpty()) {
            return false;
        }
        Lead lead = leadOpt.get();
        if (lead.isProposalLocked()) {
            throw new IllegalStateException("This proposal is locked. Please contact your agent to make changes.");
        }
        List<LeadProposal> items = leadProposalRepository.findByLeadId(leadId);
        Map<String, LeadProposal> byId = items.stream()
                .collect(Collectors.toMap(LeadProposal::getId, Function.identity()));

        for (String id : selectedItemIds) {
            LeadProposal item = byId.get(id);
            if (item == null || item.getOptionGroup() == null) {
                throw new IllegalArgumentException("Invalid selection: " + id);
            }
        }

        Map<String, String> chosenIdByGroup = new java.util.HashMap<>();
        for (String id : selectedItemIds) {
            chosenIdByGroup.put(byId.get(id).getOptionGroup(), id);
        }

        LocalDateTime now = LocalDateTime.now();
        List<LeadProposal> changed = new java.util.ArrayList<>();
        for (LeadProposal item : items) {
            String group = item.getOptionGroup();
            if (group == null || !chosenIdByGroup.containsKey(group)) {
                continue;
            }
            boolean isTarget = item.getId().equals(chosenIdByGroup.get(group));
            item.setSelected(isTarget);
            if (isTarget) {
                item.setSelectedBy("CUSTOMER");
                item.setSelectedAt(now);
            }
            changed.add(item);
        }
        leadProposalRepository.saveAll(changed);
        lead.setProposalLocked(true);
        leadRepository.save(lead);
        leadTimelineService.recordCustomerAction(leadId, null, LeadTimelineEventType.PROPOSAL_OPTION_SELECTED,
                "Customer selected " + selectedItemIds.size() + " option(s) on the shared proposal");
        leadTimelineService.recordCustomerAction(leadId, null, LeadTimelineEventType.PROPOSAL_LOCKED,
                "Proposal auto-locked after customer confirmed their choices");
        return true;
    }

    /**
     * Idempotent: approving an already-approved proposal is a no-op success. Every option block
     * must have a saved choice first - otherwise the agency would get an approval of an
     * incomplete trip at a total the customer never saw. Approving commits the choices, so the
     * proposal locks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean approve(String leadId) {
        Optional<Lead> leadOpt = leadRepository.findById(leadId);
        if (leadOpt.isEmpty()) {
            return false;
        }
        Lead lead = leadOpt.get();
        if (isApproved(leadId)) {
            return true;
        }
        Map<String, Boolean> groupDecided = new java.util.HashMap<>();
        for (LeadProposal item : quotedItems(leadId)) {
            if (item.getOptionGroup() != null) {
                groupDecided.merge(item.getOptionGroup(), item.isSelected(), Boolean::logicalOr);
            }
        }
        if (groupDecided.containsValue(false)) {
            throw new IllegalStateException("Pick one option in every block and confirm your choices before approving.");
        }
        if (!ALREADY_PAST_NEGOTIATING.contains(lead.getStatus())) {
            lead.setStatus(LeadStatus.NEGOTIATING);
        }
        lead.setProposalLocked(true);
        leadRepository.save(lead);
        leadTimelineService.recordCustomerAction(leadId, null, LeadTimelineEventType.PROPOSAL_APPROVED,
                "Customer approved the shared proposal");
        return true;
    }

    /** Approved, and nothing on the proposal changed since. */
    private boolean isApproved(String leadId) {
        return leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(com.voyra.crm.entity.LeadTimeline::getEventType)
                .filter(t -> t == LeadTimelineEventType.PROPOSAL_APPROVED || PROPOSAL_CHANGES.contains(t))
                .findFirst()
                .map(t -> t == LeadTimelineEventType.PROPOSAL_APPROVED)
                .orElse(false);
    }

    /** Lines a customer is quoted: cancelled services are never shown or counted. */
    private List<LeadProposal> quotedItems(String leadId) {
        Map<String, ServiceStatus> statusByServiceId = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId).stream()
                .collect(Collectors.toMap(com.voyra.crm.entity.LeadService::getId, com.voyra.crm.entity.LeadService::getStatus));
        return leadProposalRepository.findByLeadId(leadId).stream()
                .filter(i -> i.getServiceId() == null || statusByServiceId.get(i.getServiceId()) != ServiceStatus.CANCELLED)
                .toList();
    }

    private PublicProposalResponse buildResponse(Lead lead) {
        List<com.voyra.crm.entity.LeadService> services = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(lead.getId());
        Map<String, ServiceStatus> statusByServiceId = services.stream()
                .collect(Collectors.toMap(com.voyra.crm.entity.LeadService::getId, com.voyra.crm.entity.LeadService::getStatus));
        Map<String, String> labelByServiceId = services.stream()
                .collect(Collectors.toMap(com.voyra.crm.entity.LeadService::getId, com.voyra.crm.entity.LeadService::getLabel));

        // Cancelled work is never quoted to a customer - excluded from the items list
        // entirely here, matching (and, for this endpoint, fixing) the internal
        // recomputeQuotedTotals() rule, which this previously did not.
        var items = leadProposalRepository.findByLeadId(lead.getId()).stream()
                .filter(i -> i.getServiceId() == null || statusByServiceId.get(i.getServiceId()) != ServiceStatus.CANCELLED)
                .toList();

        BigDecimal grandTotal = items.stream()
                .filter(i -> i.getOptionGroup() == null || i.isSelected())
                .map(LeadProposal::getSellingPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Aggregate headcount only. The named traveller manifest deliberately never crosses
        // this boundary - it carries passport numbers and dates of birth, and this endpoint
        // needs no login.
        int guestCount = safe(lead.getTotalTravellers());

        return PublicProposalResponse.builder()
                .clientName(lead.getClientName())
                .destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom())
                .travelDateTo(lead.getTravelDateTo())
                .guestCount(guestCount)
                .items(items.stream()
                        .map(i -> PublicProposalItemResponse.builder()
                                .id(i.getId()).type(i.getType())
                                .serviceId(i.getServiceId()).serviceLabel(labelByServiceId.get(i.getServiceId()))
                                .description(i.getDescription())
                                .sellingPrice(i.getSellingPrice())
                                .optionGroup(i.getOptionGroup()).selected(i.isSelected())
                                .build())
                        .toList())
                .grandTotal(grandTotal)
                .locked(lead.isProposalLocked())
                .approved(isApproved(lead.getId()))
                .build();
    }

    private int safe(Integer value) {
        return value != null ? value : 0;
    }
}
