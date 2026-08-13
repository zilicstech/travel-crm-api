package com.voyra.crm.service;

import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadFollowUpUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.dto.VisaTrackerResponse;
import com.voyra.crm.dto.VisaTrackerUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadNote;
import com.voyra.crm.entity.ProposalItem;
import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.ProposalItemRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.MarginCalculator;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    private static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);

    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final ProposalItemRepository proposalItemRepository;
    private final AgentRepository agentRepository;
    private final AuthorResolver authorResolver;

    @Transactional
    public LeadDetailResponse createLead(LeadCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAssignedTo());
        GuestDetails guests = request.getGuestDetails() != null ? request.getGuestDetails() : new GuestDetails();

        boolean hasVisa = request.getCategories().contains(LeadCategory.VISA);

        Lead lead = Lead.builder()
                .id(generateUniqueLeadId())
                .customerId(request.getCustomerId())
                .name(request.getName())
                .email(request.getEmail())
                .countryCode(request.getCountryCode())
                .phone(request.getPhone())
                .destination(request.getDestination())
                .travelDateFrom(request.getTravelDateFrom())
                .travelDateTo(request.getTravelDateTo())
                .budget(request.getBudget())
                .status(LeadStatus.NEW)
                .source(request.getSource() != null ? request.getSource() : LeadSource.PHONE_CALL)
                .priority(request.getPriority() != null ? request.getPriority() : LeadPriority.MEDIUM)
                .categories(request.getCategories())
                .assignedTo(owner.id())
                .assignedAgentName(owner.name())
                .followUpDate(request.getFollowUpDate())
                .adults(guests.getAdults() != null ? guests.getAdults() : 1)
                .children(guests.getChildren() != null ? guests.getChildren() : 0)
                .infants(guests.getInfants() != null ? guests.getInfants() : 0)
                .specialRequirements(guests.getSpecialRequirements())
                .passportCollected(hasVisa ? false : null)
                .photosCollected(hasVisa ? false : null)
                .formsFilled(hasVisa ? false : null)
                .submittedToEmbassy(hasVisa ? false : null)
                .approved(hasVisa ? false : null)
                .createdDate(LocalDateTime.now())
                .build();
        leadRepository.save(lead);

        log.info("Lead created: leadId={}, assignedTo={}", lead.getId(), owner.id());
        return toDetailResponse(lead);
    }

    @Transactional(readOnly = true)
    public List<LeadResponse> listLeads(LeadStatus statusFilter) {
        return scopedLeads(statusFilter).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<LeadResponse> listLeads(LeadStatus statusFilter, Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Page<Lead> page;
        if (principal.isAgent()) {
            page = statusFilter == null
                    ? leadRepository.findByAssignedTo(principal.userId(), pageable)
                    : leadRepository.findByAssignedToAndStatus(principal.userId(), statusFilter, pageable);
        } else {
            page = statusFilter == null
                    ? leadRepository.findAll(pageable)
                    : leadRepository.findByStatus(statusFilter, pageable);
        }
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * Every combination resolves to an indexed derived query - never a full table read
     * filtered in Java. Agent callers are structurally confined to their own assigned rows.
     */
    private List<Lead> scopedLeads(LeadStatus statusFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            return statusFilter == null
                    ? leadRepository.findByAssignedTo(principal.userId())
                    : leadRepository.findByAssignedToAndStatus(principal.userId(), statusFilter);
        }
        return statusFilter == null
                ? leadRepository.findAll()
                : leadRepository.findByStatus(statusFilter);
    }

    @Transactional(readOnly = true)
    public LeadDetailResponse getLead(String id) {
        return toDetailResponse(findAccessibleLead(id));
    }

    @Transactional
    public LeadDetailResponse updateStatus(String id, LeadStatusUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        // Business rule (BRD): lost leads require a mandatory reason.
        if (request.getStatus() == LeadStatus.LOST
                && (request.getLostReason() == null || request.getLostReason().isBlank())) {
            throw new IllegalArgumentException("A reason is required when marking a lead as Lost");
        }
        lead.setStatus(request.getStatus());
        lead.setLostReason(request.getStatus() == LeadStatus.LOST ? request.getLostReason() : null);
        leadRepository.save(lead);
        log.info("Lead status updated: leadId={}, status={}", id, request.getStatus());
        return toDetailResponse(lead);
    }

    @Transactional
    public LeadDetailResponse updateFollowUp(String id, LeadFollowUpUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        lead.setFollowUpDate(request.getFollowUpDate());
        leadRepository.save(lead);
        return toDetailResponse(lead);
    }

    /** Owner-only, enforced at the controller layer - only the Owner may reassign a lead to a different agent. */
    @Transactional
    public LeadDetailResponse assignAgent(String id, String agentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));
        Agent agent = agentRepository.findByIdAndTenantId(agentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + agentId));

        lead.setAssignedTo(agent.getId());
        lead.setAssignedAgentName(agent.getName());
        leadRepository.save(lead);
        log.info("Lead reassigned: leadId={}, agentId={}", id, agentId);
        return toDetailResponse(lead);
    }

    @Transactional
    public LeadNoteResponse addNote(String id, LeadNoteCreateRequest request) {
        findAccessibleLead(id);
        AuthorResolver.AuthorInfo author = authorResolver.resolveCurrentAuthor();

        LeadNote note = LeadNote.builder()
                .id(UniqueIdResolver.resolve(leadNoteRepository::existsById))
                .leadId(id)
                .authorAgentId(author.id())
                .authorName(author.name())
                .text(request.getText())
                .createdDate(LocalDateTime.now())
                .build();
        leadNoteRepository.save(note);
        return LeadNoteResponse.builder()
                .id(note.getId()).authorAgentId(author.id()).authorName(author.name())
                .text(note.getText()).createdDate(note.getCreatedDate()).build();
    }

    @Transactional
    public ProposalItemResponse addProposalItem(String id, ProposalItemCreateRequest request) {
        findAccessibleLead(id);
        BigDecimal netCost = request.getNetCost() != null ? request.getNetCost() : BigDecimal.ZERO;
        BigDecimal sellingPrice = request.getSellingPrice() != null ? request.getSellingPrice() : BigDecimal.ZERO;

        ProposalItem item = ProposalItem.builder()
                .id(UniqueIdResolver.resolve(proposalItemRepository::existsById))
                .leadId(id)
                .type(request.getType())
                .description(request.getDescription())
                .supplier(request.getSupplier())
                .netCost(netCost)
                .sellingPrice(sellingPrice)
                .createdDate(LocalDateTime.now())
                .build();
        proposalItemRepository.save(item);
        log.info("Proposal item added: leadId={}, itemId={}", id, item.getId());
        return toProposalItemResponse(item);
    }

    @Transactional
    public void removeProposalItem(String id, String itemId) {
        findAccessibleLead(id);
        proposalItemRepository.deleteByIdAndLeadId(itemId, id);
        log.info("Proposal item removed: leadId={}, itemId={}", id, itemId);
    }

    @Transactional
    public LeadDetailResponse updateVisaTracker(String id, VisaTrackerUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        if (request.getPassportCollected() != null) lead.setPassportCollected(request.getPassportCollected());
        if (request.getPhotosCollected() != null) lead.setPhotosCollected(request.getPhotosCollected());
        if (request.getFormsFilled() != null) lead.setFormsFilled(request.getFormsFilled());
        if (request.getSubmittedToEmbassy() != null) lead.setSubmittedToEmbassy(request.getSubmittedToEmbassy());
        if (request.getApproved() != null) lead.setApproved(request.getApproved());
        leadRepository.save(lead);
        return toDetailResponse(lead);
    }

    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("assignedTo is required when an Owner creates a lead");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    /** Shared ownership check reused by ProposalLinkService. */
    public Lead findAccessibleLead(String id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !lead.getAssignedTo().equals(principal.userId())) {
            throw new AccessDeniedException("This lead is not assigned to you");
        }
        return lead;
    }

    private boolean isOverdue(Lead lead) {
        return lead.getFollowUpDate() != null
                && lead.getFollowUpDate().isBefore(LocalDate.now())
                && !TERMINAL_STATUSES.contains(lead.getStatus());
    }

    private LeadResponse toResponse(Lead lead) {
        return LeadResponse.builder()
                .id(lead.getId()).customerId(lead.getCustomerId()).name(lead.getName()).email(lead.getEmail())
                .countryCode(lead.getCountryCode()).phone(lead.getPhone()).destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom()).travelDateTo(lead.getTravelDateTo())
                .categories(lead.getCategories()).budget(lead.getBudget()).status(lead.getStatus())
                .source(lead.getSource()).priority(lead.getPriority()).assignedTo(lead.getAssignedTo())
                .assignedAgentName(lead.getAssignedAgentName()).followUpDate(lead.getFollowUpDate())
                .createdDate(lead.getCreatedDate()).overdue(isOverdue(lead))
                .build();
    }

    private LeadDetailResponse toDetailResponse(Lead lead) {
        List<ProposalItem> items = proposalItemRepository.findByLeadId(lead.getId());
        List<ProposalItemResponse> itemResponses = items.stream().map(this::toProposalItemResponse).toList();

        BigDecimal totalNet = items.stream().map(ProposalItem::getNetCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSelling = items.stream().map(ProposalItem::getSellingPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LeadNoteResponse> notes = leadNoteRepository.findByLeadIdOrderByCreatedDateDesc(lead.getId()).stream()
                .map(n -> LeadNoteResponse.builder().id(n.getId()).authorAgentId(n.getAuthorAgentId())
                        .authorName(n.getAuthorName()).text(n.getText()).createdDate(n.getCreatedDate()).build())
                .toList();

        boolean hasVisa = lead.getCategories() != null && lead.getCategories().contains(LeadCategory.VISA);

        return LeadDetailResponse.builder()
                .id(lead.getId()).customerId(lead.getCustomerId()).name(lead.getName()).email(lead.getEmail())
                .countryCode(lead.getCountryCode()).phone(lead.getPhone()).destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom()).travelDateTo(lead.getTravelDateTo())
                .categories(lead.getCategories()).budget(lead.getBudget()).status(lead.getStatus())
                .source(lead.getSource()).priority(lead.getPriority()).assignedTo(lead.getAssignedTo())
                .assignedAgentName(lead.getAssignedAgentName()).followUpDate(lead.getFollowUpDate())
                .lostReason(lead.getLostReason()).createdDate(lead.getCreatedDate()).overdue(isOverdue(lead))
                .guestDetails(GuestDetails.builder().adults(lead.getAdults()).children(lead.getChildren())
                        .infants(lead.getInfants()).specialRequirements(lead.getSpecialRequirements()).build())
                .visaTracker(hasVisa ? VisaTrackerResponse.builder()
                        .passportCollected(lead.getPassportCollected()).photosCollected(lead.getPhotosCollected())
                        .formsFilled(lead.getFormsFilled()).submittedToEmbassy(lead.getSubmittedToEmbassy())
                        .approved(lead.getApproved()).build() : null)
                .proposalItems(itemResponses)
                .totalNetCost(totalNet)
                .totalSellingPrice(totalSelling)
                .marginPercent(MarginCalculator.marginPercent(totalNet, totalSelling))
                .notes(notes)
                .hasPublicProposalLink(lead.getPublicProposalToken() != null)
                .build();
    }

    private ProposalItemResponse toProposalItemResponse(ProposalItem item) {
        return ProposalItemResponse.builder()
                .id(item.getId()).type(item.getType()).description(item.getDescription())
                .supplier(item.getSupplier()).netCost(item.getNetCost()).sellingPrice(item.getSellingPrice())
                .marginPercent(MarginCalculator.marginPercent(item.getNetCost(), item.getSellingPrice()))
                .build();
    }

    private String generateUniqueLeadId() {
        return UniqueIdResolver.resolve(leadRepository::existsById);
    }
}
