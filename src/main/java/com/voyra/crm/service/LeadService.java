package com.voyra.crm.service;

import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadFollowUpUpdateRequest;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadNote;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.MarginCalculator;
import com.voyra.crm.util.PaxTypeCalculator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);

    /** Travellers who are still expected to fly. DROPPED rows stay for history but stop counting. */
    private static final Set<LeadMemberStatus> ACTIVE_MANIFEST_STATUSES =
            Set.of(LeadMemberStatus.TENTATIVE, LeadMemberStatus.CONFIRMED);

    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final LeadProposalRepository leadProposalRepository;
    private final LeadMemberRepository leadMemberRepository;
    private final MemberRepository memberRepository;
    private final AgentRepository agentRepository;
    private final ClientService clientService;
    private final MemberService memberService;
    private final LeadTimelineService leadTimelineService;
    private final AuthorResolver authorResolver;

    @Transactional
    public LeadDetailResponse createLead(LeadCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAssignedTo());
        Client client = clientService.findAccessibleClient(request.getClientId());
        GuestDetails guests = request.getGuestDetails() != null ? request.getGuestDetails() : new GuestDetails();

        int adults = guests.getAdults() != null ? guests.getAdults() : 1;
        List<Integer> kidAges = guests.getKidAges() != null ? guests.getKidAges() : List.of();
        int kids = guests.getKids() != null ? guests.getKids() : kidAges.size();

        Lead lead = Lead.builder()
                .id(UniqueIdResolver.resolve(leadRepository::existsById))
                .clientId(client.getId())
                .clientName(client.getName())
                .clientType(client.getType())
                .destination(request.getDestination())
                .travelDateFrom(request.getTravelDateFrom())
                .travelDateTo(request.getTravelDateTo())
                .adults(adults)
                .kids(kids)
                .kidAges(kidAges)
                .totalTravellers(adults + kids)
                .leadDescription(request.getLeadDescription())
                .preferences(request.getPreferences())
                .status(LeadStatus.NEW)
                .source(request.getSource() != null ? request.getSource() : "PHONE_CALL")
                .priority(request.getPriority() != null ? request.getPriority() : LeadPriority.MEDIUM)
                .categories(request.getCategories())
                .budget(request.getBudget())
                .assignedTo(owner.id())
                .assignedAgentName(owner.name())
                .followUpDate(request.getFollowUpDate())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        leadRepository.save(lead);

        leadTimelineService.recordTransition(lead.getId(), LeadTimelineEventType.LEAD_CREATED,
                null, LeadStatus.NEW, "Lead created for " + client.getName() + " to " + lead.getDestination());

        log.info("Lead created: leadId={}, clientId={}, assignedTo={}", lead.getId(), client.getId(), owner.id());
        return toDetailResponse(lead);
    }

    @Transactional(readOnly = true)
    public List<LeadResponse> listLeads(LeadStatus statusFilter) {
        List<Lead> leads = scopedLeads(statusFilter);
        Map<String, Long> confirmedByLead = confirmedCounts(leads);
        return leads.stream().map(l -> toResponse(l, confirmedByLead)).toList();
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
        Map<String, Long> confirmedByLead = confirmedCounts(page.getContent());
        return PagedResponse.from(page, l -> toResponse(l, confirmedByLead));
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

    /** One manifest query for a whole page rather than one per lead. */
    private Map<String, Long> confirmedCounts(List<Lead> leads) {
        if (leads.isEmpty()) {
            return Map.of();
        }
        return leadMemberRepository.findByLeadIdIn(leads.stream().map(Lead::getId).toList()).stream()
                .filter(lm -> lm.getStatus() == LeadMemberStatus.CONFIRMED)
                .collect(Collectors.groupingBy(LeadMember::getLeadId, Collectors.counting()));
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
        LeadStatus previous = lead.getStatus();
        lead.setStatus(request.getStatus());
        lead.setLostReason(request.getStatus() == LeadStatus.LOST ? request.getLostReason() : null);
        touch(lead);
        leadRepository.save(lead);

        leadTimelineService.recordTransition(id, LeadTimelineEventType.STATUS_CHANGED, previous,
                request.getStatus(), "Status changed from " + previous + " to " + request.getStatus());

        log.info("Lead status updated: leadId={}, status={}", id, request.getStatus());
        return toDetailResponse(lead);
    }

    @Transactional
    public LeadDetailResponse updateFollowUp(String id, LeadFollowUpUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        lead.setFollowUpDate(request.getFollowUpDate());
        touch(lead);
        leadRepository.save(lead);
        leadTimelineService.record(id, LeadTimelineEventType.FOLLOW_UP_SET,
                "Follow-up set for " + request.getFollowUpDate());
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
        touch(lead);
        leadRepository.save(lead);

        leadTimelineService.record(id, LeadTimelineEventType.ASSIGNED, "Assigned to " + agent.getName());

        log.info("Lead reassigned: leadId={}, agentId={}", id, agentId);
        return toDetailResponse(lead);
    }

    // ---------------------------------------------------------------------
    // Traveller manifest
    // ---------------------------------------------------------------------

    /**
     * Attaches travellers to a lead, either picked from the client's existing roster or created
     * ad hoc and attached in the same call.
     *
     * <p>Ad-hoc travellers become members of the lead's client rather than living only on the
     * lead. A friend joining a family trip is very often on the next trip too, and a traveller
     * who exists only inside one enquiry has to be re-typed - passport and all - every time.
     *
     * <p>An existing member must already belong to this lead's client. Allowing a cross-client
     * pick would let one client's manifest expose another client's passport data.
     */
    @Transactional
    public List<LeadMemberResponse> addMembers(String leadId, LeadMemberAddRequest request) {
        Lead lead = findAccessibleLead(leadId);
        List<String> existingIds = request.getMemberIds() != null ? request.getMemberIds() : List.of();
        List<MemberCreateRequest> newMembers = request.getNewMembers() != null ? request.getNewMembers() : List.of();

        if (existingIds.isEmpty() && newMembers.isEmpty()) {
            throw new IllegalArgumentException("Select at least one traveller to add");
        }

        List<Member> toAttach = new ArrayList<>();
        for (String memberId : existingIds) {
            toAttach.add(memberRepository.findByMemberIdAndClientId(memberId, lead.getClientId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Member " + memberId + " does not belong to this lead's client")));
        }
        for (MemberCreateRequest newMember : newMembers) {
            toAttach.add(memberService.persistMember(lead.getClientId(), newMember));
        }

        List<LeadMember> attached = new ArrayList<>();
        for (Member member : toAttach) {
            if (leadMemberRepository.existsByLeadIdAndMemberId(leadId, member.getMemberId())) {
                throw new IllegalStateException(member.getName() + " is already a traveller on this lead");
            }
            LeadMember row = LeadMember.builder()
                    .id(UniqueIdResolver.resolve(leadMemberRepository::existsById))
                    .leadId(leadId)
                    .memberId(member.getMemberId())
                    .clientId(lead.getClientId())
                    .memberName(member.getName())
                    .status(LeadMemberStatus.TENTATIVE)
                    .createdAt(LocalDateTime.now())
                    .createdBy(currentUserId())
                    .build();
            leadMemberRepository.save(row);
            attached.add(row);
            leadTimelineService.record(leadId, LeadTimelineEventType.MEMBER_ADDED,
                    member.getName() + " added as a traveller");
        }

        touch(lead);
        leadRepository.save(lead);
        log.info("Travellers added to lead: leadId={}, count={}", leadId, attached.size());

        Map<String, Member> byId = toAttach.stream()
                .collect(Collectors.toMap(Member::getMemberId, Function.identity()));
        return attached.stream().map(row -> toMemberResponse(row, byId.get(row.getMemberId()), lead)).toList();
    }

    /**
     * Updates one traveller's inclusion status and document checklist.
     *
     * <p>There is no delete counterpart on purpose. A traveller leaves the party by moving to
     * DROPPED with a reason: by the time someone pulls out, their passport has usually been
     * collected and their visa may already be filed, and deleting the row would erase both the
     * fact and the paperwork trail.
     */
    @Transactional
    public LeadMemberResponse updateMember(String leadId, String memberId, LeadMemberUpdateRequest request) {
        Lead lead = findAccessibleLead(leadId);
        LeadMember row = leadMemberRepository.findByLeadIdAndMemberId(leadId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("Traveller not found on this lead: " + memberId));

        if (request.getStatus() != null) {
            if (request.getStatus() == LeadMemberStatus.DROPPED
                    && (request.getDroppedReason() == null || request.getDroppedReason().isBlank())) {
                throw new IllegalArgumentException("A reason is required when dropping a traveller");
            }
            row.setStatus(request.getStatus());
            row.setDroppedReason(
                    request.getStatus() == LeadMemberStatus.DROPPED ? request.getDroppedReason() : null);
            leadTimelineService.record(leadId,
                    request.getStatus() == LeadMemberStatus.DROPPED
                            ? LeadTimelineEventType.MEMBER_DROPPED
                            : LeadTimelineEventType.MEMBER_UPDATED,
                    row.getMemberName() + " is now " + request.getStatus());
        }

        row.setModifiedAt(LocalDateTime.now());
        row.setModifiedBy(currentUserId());
        leadMemberRepository.save(row);

        log.info("Lead traveller updated: leadId={}, memberId={}, status={}", leadId, memberId, row.getStatus());
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberId));
        return toMemberResponse(row, member, lead);
    }

    @Transactional(readOnly = true)
    public List<LeadMemberResponse> listMembers(String leadId) {
        Lead lead = findAccessibleLead(leadId);
        return manifestFor(lead);
    }

    // ---------------------------------------------------------------------
    // Notes, proposal, timeline
    // ---------------------------------------------------------------------

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
                .createdAt(LocalDateTime.now())
                .build();
        leadNoteRepository.save(note);
        leadTimelineService.record(id, LeadTimelineEventType.NOTE_ADDED, "Note added by " + author.name());
        return toNoteResponse(note);
    }

    @Transactional
    public ProposalItemResponse addProposalItem(String id, ProposalItemCreateRequest request) {
        findAccessibleLead(id);
        BigDecimal netCost = request.getNetCost() != null ? request.getNetCost() : BigDecimal.ZERO;
        BigDecimal sellingPrice = request.getSellingPrice() != null ? request.getSellingPrice() : BigDecimal.ZERO;

        LeadProposal item = LeadProposal.builder()
                .id(UniqueIdResolver.resolve(leadProposalRepository::existsById))
                .leadId(id)
                .type(request.getType())
                .description(request.getDescription())
                .supplier(request.getSupplier())
                .netCost(netCost)
                .sellingPrice(sellingPrice)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        leadProposalRepository.save(item);
        leadTimelineService.record(id, LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
                "Proposal item added: " + item.getDescription());
        log.info("Proposal item added: leadId={}, itemId={}", id, item.getId());
        return toProposalItemResponse(item);
    }

    @Transactional
    public void removeProposalItem(String id, String itemId) {
        findAccessibleLead(id);
        LeadProposal item = leadProposalRepository.findByIdAndLeadId(itemId, id)
                .orElseThrow(() -> new IllegalArgumentException("Proposal item not found: " + itemId));
        leadProposalRepository.delete(item);
        leadTimelineService.record(id, LeadTimelineEventType.PROPOSAL_ITEM_REMOVED,
                "Proposal item removed: " + item.getDescription());
        log.info("Proposal item removed: leadId={}, itemId={}", id, itemId);
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

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

    private void touch(Lead lead) {
        lead.setTotalTravellers(lead.getAdults() + lead.getKids());
        lead.setUpdatedAt(LocalDateTime.now());
        lead.setUpdatedBy(currentUserId());
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private boolean isOverdue(Lead lead) {
        return lead.getFollowUpDate() != null
                && lead.getFollowUpDate().isBefore(LocalDate.now())
                && !TERMINAL_STATUSES.contains(lead.getStatus());
    }

    private List<LeadMemberResponse> manifestFor(Lead lead) {
        List<LeadMember> rows = leadMemberRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Member> byId = memberRepository
                .findAllById(rows.stream().map(LeadMember::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getMemberId, Function.identity()));
        return rows.stream().map(row -> toMemberResponse(row, byId.get(row.getMemberId()), lead)).toList();
    }

    /**
     * Identity fields come from the member; checklist and inclusion state come from the
     * manifest row. Pax type is computed against this lead's departure date, so the same
     * person can legitimately be a child on one lead and an adult on another.
     */
    private LeadMemberResponse toMemberResponse(LeadMember row, Member member, Lead lead) {
        LocalDate departure = lead.getTravelDateFrom();
        return LeadMemberResponse.builder()
                .id(row.getId())
                .memberId(row.getMemberId())
                .memberName(row.getMemberName())
                .relation(member != null ? member.getRelation() : null)
                .status(row.getStatus())
                .droppedReason(row.getDroppedReason())
                .dob(member != null ? member.getDob() : null)
                .gender(member != null ? member.getGender() : null)
                .nationality(member != null ? member.getNationality() : null)
                .passportNumber(member != null ? member.getPassportNumber() : null)
                .passportExpiry(member != null ? member.getPassportExpiry() : null)
                .paxType(member != null ? PaxTypeCalculator.paxTypeAt(member.getDob(), departure) : PaxType.UNKNOWN)
                .ageAtTravel(member != null ? PaxTypeCalculator.ageAt(member.getDob(), departure) : null)
                .crossesPaxBoundary(member != null && PaxTypeCalculator.crossesPaxBoundary(
                        member.getDob(), departure, lead.getTravelDateTo()))
                .documentsComplete(member != null && hasRequiredFields(member, lead.getCategories()))
                .createdAt(row.getCreatedAt())
                .build();
    }

    /**
     * Which identity fields a traveller must carry depends on what the lead is selling. The
     * four legacy category names (HOTEL/HOLIDAY_PACKAGE/FLIGHT/VISA) carry an explicit rule
     * each; categories now come from agency_setting rather than a fixed Java enum, so any other
     * agency-defined category (a "Cruise Package" or "Group Tour") falls back to requiring only
     * a name rather than crashing on an unrecognised value.
     */
    private boolean hasRequiredFields(Member member, List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return isPresent(member.getName());
        }
        boolean ok = isPresent(member.getName());
        for (String category : categories) {
            ok = ok && switch (category) {
                case "HOTEL" -> true;
                case "HOLIDAY_PACKAGE" -> member.getDob() != null;
                case "FLIGHT" -> member.getDob() != null && isPresent(member.getGender());
                case "VISA" -> member.getDob() != null && isPresent(member.getGender())
                        && isPresent(member.getPassportNumber()) && member.getPassportExpiry() != null
                        && isPresent(member.getNationality());
                default -> true;
            };
        }
        return ok;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private LeadResponse toResponse(Lead lead, Map<String, Long> confirmedByLead) {
        return LeadResponse.builder()
                .id(lead.getId()).clientId(lead.getClientId()).clientName(lead.getClientName())
                .clientType(lead.getClientType()).destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom()).travelDateTo(lead.getTravelDateTo())
                .categories(lead.getCategories()).budget(lead.getBudget()).status(lead.getStatus())
                .source(lead.getSource()).priority(lead.getPriority())
                .totalTravellers(lead.getTotalTravellers())
                .confirmedTravellers(confirmedByLead.getOrDefault(lead.getId(), 0L).intValue())
                .assignedTo(lead.getAssignedTo()).assignedAgentName(lead.getAssignedAgentName())
                .followUpDate(lead.getFollowUpDate()).createdAt(lead.getCreatedAt()).overdue(isOverdue(lead))
                .build();
    }

    private LeadDetailResponse toDetailResponse(Lead lead) {
        List<LeadProposal> items = leadProposalRepository.findByLeadId(lead.getId());
        List<ProposalItemResponse> itemResponses = items.stream().map(this::toProposalItemResponse).toList();

        BigDecimal totalNet = items.stream().map(LeadProposal::getNetCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSelling = items.stream().map(LeadProposal::getSellingPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LeadNoteResponse> notes = leadNoteRepository.findByLeadIdOrderByCreatedAtDesc(lead.getId()).stream()
                .map(this::toNoteResponse).toList();

        List<LeadMemberResponse> manifest = manifestFor(lead);

        Member primary = memberRepository
                .findByClientIdAndTypeAndIsActiveTrue(lead.getClientId(), MemberType.CLIENT).orElse(null);

        return LeadDetailResponse.builder()
                .id(lead.getId()).clientId(lead.getClientId()).clientName(lead.getClientName())
                .clientType(lead.getClientType())
                .contactEmail(primary != null ? primary.getEmail() : null)
                .contactCountryCode(primary != null ? primary.getCountryCode() : null)
                .contactPhone(primary != null ? primary.getPhone() : null)
                .destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom()).travelDateTo(lead.getTravelDateTo())
                .categories(lead.getCategories()).budget(lead.getBudget()).status(lead.getStatus())
                .source(lead.getSource()).priority(lead.getPriority()).assignedTo(lead.getAssignedTo())
                .assignedAgentName(lead.getAssignedAgentName()).followUpDate(lead.getFollowUpDate())
                .lostReason(lead.getLostReason()).leadDescription(lead.getLeadDescription())
                .preferences(lead.getPreferences())
                .specialNotes(lead.getSpecialNotes()).travelPreferences(lead.getTravelPreferences())
                .createdAt(lead.getCreatedAt()).updatedAt(lead.getUpdatedAt()).overdue(isOverdue(lead))
                .guestDetails(toGuestDetails(lead))
                .members(manifest)
                .manifestComplete(isManifestComplete(lead, manifest))
                .proposalItems(itemResponses)
                .totalNetCost(totalNet)
                .totalSellingPrice(totalSelling)
                .marginPercent(MarginCalculator.marginPercent(totalNet, totalSelling))
                .notes(notes)
                .hasPublicProposalLink(lead.getPublicProposalToken() != null)
                .build();
    }

    private GuestDetails toGuestDetails(Lead lead) {
        List<Integer> kidAges = lead.getKidAges() != null ? lead.getKidAges() : List.of();
        return GuestDetails.builder()
                .adults(lead.getAdults())
                .kids(lead.getKids())
                .kidAges(kidAges)
                .infants((int) kidAges.stream().filter(age -> age != null && age < 2).count())
                .totalTravellers(lead.getTotalTravellers())
                .build();
    }

    /**
     * The manifest is complete when every traveller still expected to fly is named, carries the
     * fields this lead's categories require, and the named count matches the headcount the
     * client gave. Confirming a booking or filing a visa should be gated on this rather than on
     * the agent remembering to check.
     */
    private boolean isManifestComplete(Lead lead, List<LeadMemberResponse> manifest) {
        List<LeadMemberResponse> active = manifest.stream()
                .filter(m -> ACTIVE_MANIFEST_STATUSES.contains(m.getStatus()))
                .toList();
        if (active.isEmpty() || active.size() != lead.getTotalTravellers()) {
            return false;
        }
        return active.stream().allMatch(m -> Boolean.TRUE.equals(m.getDocumentsComplete()));
    }

    private LeadNoteResponse toNoteResponse(LeadNote note) {
        return LeadNoteResponse.builder()
                .id(note.getId()).authorAgentId(note.getAuthorAgentId()).authorName(note.getAuthorName())
                .text(note.getText()).createdDate(note.getCreatedAt())
                .build();
    }

    private ProposalItemResponse toProposalItemResponse(LeadProposal item) {
        return ProposalItemResponse.builder()
                .id(item.getId()).type(item.getType()).description(item.getDescription())
                .supplier(item.getSupplier()).netCost(item.getNetCost()).sellingPrice(item.getSellingPrice())
                .marginPercent(MarginCalculator.marginPercent(item.getNetCost(), item.getSellingPrice()))
                .build();
    }
}
