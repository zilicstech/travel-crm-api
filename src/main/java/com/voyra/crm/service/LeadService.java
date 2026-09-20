package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailUpdateRequest;
import com.voyra.crm.dto.ProposalItemUpdateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.ProposalItemBatchCreateRequest;
import com.voyra.crm.dto.ProposalItemBatchLine;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadNote;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
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
    private static final String[] AUDITED = {
            "status", "lostReason", "destination", "budget", "specialNotes", "escalated", "escalationReason"
    };

    /** Travellers who are still expected to fly. DROPPED rows stay for history but stop counting. */
    private static final Set<LeadMemberStatus> ACTIVE_MANIFEST_STATUSES =
            Set.of(LeadMemberStatus.TENTATIVE, LeadMemberStatus.CONFIRMED);

    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final LeadProposalRepository leadProposalRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadMemberRepository leadMemberRepository;
    private final MemberRepository memberRepository;
    private final AgentRepository agentRepository;
    private final ClientService clientService;
    private final MemberService memberService;
    private final LeadTimelineService leadTimelineService;
    private final AuthorResolver authorResolver;
    private final ServiceInstanceService serviceInstanceService;
    private final LeadFollowUpService leadFollowUpService;
    private final BookingService bookingService;
    private final InvoiceService invoiceService;
    private final AuditService auditService;

    @Transactional
    public LeadDetailResponse createLead(LeadCreateRequest request) {
        AuthorResolver.AuthorInfo author = authorResolver.resolveCurrentAuthor();
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
                .followUpDate(request.getFollowUpDate())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(author.id())
                .createdByName(author.name())
                .build();
        leadRepository.save(lead);

        leadTimelineService.recordTransition(lead.getId(), LeadTimelineEventType.LEAD_CREATED,
                null, LeadStatus.NEW, "Lead created for " + client.getName() + " to " + lead.getDestination());
        auditService.recordCreate(AuditEntityType.LEAD, lead.getId(), lead.getClientName() + " / " + lead.getDestination());

        log.info("Lead created: leadId={}, clientId={}, createdBy={}", lead.getId(), client.getId(), author.id());
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
            List<ServiceType> types = manageableServiceTypes(principal.userId());
            if (types.isEmpty()) {
                page = statusFilter == null
                        ? leadRepository.findAccessibleToAgentWithNoManagedTypes(principal.userId(), pageable)
                        : leadRepository.findAccessibleToAgentWithNoManagedTypesAndStatus(
                                principal.userId(), statusFilter, pageable);
            } else {
                page = statusFilter == null
                        ? leadRepository.findAccessibleToAgent(principal.userId(), types, pageable)
                        : leadRepository.findAccessibleToAgentAndStatus(principal.userId(), statusFilter, types, pageable);
            }
        } else {
            page = statusFilter == null
                    ? leadRepository.findAll(pageable)
                    : leadRepository.findByStatus(statusFilter, pageable);
        }
        Map<String, Long> confirmedByLead = confirmedCounts(page.getContent());
        return PagedResponse.from(page, l -> toResponse(l, confirmedByLead));
    }

    /**
     * Every lead an agent may reach: ones they created, plus ones where they hold or
     * manage a service - the same rule {@link #findAccessibleLead} enforces on the door,
     * so the list never under-reports what an agent can already open individually.
     */
    private List<Lead> scopedLeads(LeadStatus statusFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            List<ServiceType> types = manageableServiceTypes(principal.userId());
            if (types.isEmpty()) {
                return statusFilter == null
                        ? leadRepository.findAccessibleToAgentWithNoManagedTypes(principal.userId())
                        : leadRepository.findAccessibleToAgentWithNoManagedTypesAndStatus(principal.userId(), statusFilter);
            }
            return statusFilter == null
                    ? leadRepository.findAccessibleToAgent(principal.userId(), types)
                    : leadRepository.findAccessibleToAgentAndStatus(principal.userId(), statusFilter, types);
        }
        return statusFilter == null
                ? leadRepository.findAll()
                : leadRepository.findByStatus(statusFilter);
    }

    private List<ServiceType> manageableServiceTypes(String agentId) {
        return agentRepository.findById(agentId).map(Agent::getManageableServices).orElse(List.of());
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
        Map<String, String> before = AuditSnapshot.of(lead, AUDITED);
        LeadStatus previous = lead.getStatus();
        lead.setStatus(request.getStatus());
        lead.setLostReason(request.getStatus() == LeadStatus.LOST ? request.getLostReason() : null);
        touch(lead);
        leadRepository.save(lead);

        leadTimelineService.recordTransition(id, LeadTimelineEventType.STATUS_CHANGED, previous,
                request.getStatus(), "Status changed from " + previous + " to " + request.getStatus());
        auditService.recordUpdate(AuditEntityType.LEAD, lead.getId(), lead.getClientName() + " / " + lead.getDestination(),
                AuditSnapshot.diff(before, AuditSnapshot.of(lead, AUDITED)));

        log.info("Lead status updated: leadId={}, status={}", id, request.getStatus());
        return toDetailResponse(lead);
    }

    /** Edits the trip-level facts captured at Add Lead time - destination, budget, special remarks. */
    @Transactional
    public LeadDetailResponse updateDetails(String id, LeadDetailUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        Map<String, String> before = AuditSnapshot.of(lead, AUDITED);
        if (request.getDestination() != null) {
            lead.setDestination(request.getDestination());
        }
        if (request.getBudget() != null) {
            lead.setBudget(request.getBudget());
        }
        if (request.getSpecialNotes() != null) {
            lead.setSpecialNotes(request.getSpecialNotes());
        }
        touch(lead);
        leadRepository.save(lead);

        leadTimelineService.record(id, LeadTimelineEventType.DETAILS_UPDATED, "Trip information updated");
        auditService.recordUpdate(AuditEntityType.LEAD, lead.getId(), lead.getClientName() + " / " + lead.getDestination(),
                AuditSnapshot.diff(before, AuditSnapshot.of(lead, AUDITED)));

        log.info("Lead details updated: leadId={}", id);
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
        Lead lead = findAccessibleLead(id);
        assertProposalUnlocked(lead);
        BigDecimal netCost = request.getNetCost() != null ? request.getNetCost() : BigDecimal.ZERO;
        BigDecimal sellingPrice = request.getSellingPrice() != null ? request.getSellingPrice() : BigDecimal.ZERO;

        String serviceLabel = null;
        if (request.getServiceId() != null) {
            serviceLabel = leadServiceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()))
                    .getLabel();
        }

        LeadProposal item = LeadProposal.builder()
                .id(UniqueIdResolver.resolve(leadProposalRepository::existsById))
                .leadId(id)
                .serviceId(request.getServiceId())
                .serviceLabel(serviceLabel)
                .type(request.getType())
                .description(request.getDescription())
                .supplier(request.getSupplier())
                .netCost(netCost)
                .sellingPrice(sellingPrice)
                .optionGroup(request.getOptionGroup())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        leadProposalRepository.save(item);
        if (request.getOptionGroup() != null && request.isSelected()) {
            enforceSingleSelection(id, request.getOptionGroup(), item.getId(), "AGENT");
        }
        recomputeQuotedTotals(lead);
        leadTimelineService.record(id, request.getServiceId(), LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
                "Proposal item added: " + item.getDescription());
        log.info("Proposal item added: leadId={}, itemId={}", id, item.getId());
        return toProposalItemResponse(leadProposalRepository.findByIdAndLeadId(item.getId(), id).orElse(item));
    }

    /**
     * Creates every offered line under one option group in a single transaction - the shape a
     * supplier search modal needs, since ticking three flight offers and clicking "Add to
     * Proposal" must not be three separate round trips that could partially fail.
     */
    @Transactional
    public List<ProposalItemResponse> addProposalItemsBatch(String id, ProposalItemBatchCreateRequest request) {
        Lead lead = findAccessibleLead(id);
        assertProposalUnlocked(lead);
        String serviceLabel = null;
        if (request.getServiceId() != null) {
            serviceLabel = leadServiceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()))
                    .getLabel();
        }
        String optionGroup = request.getOptionGroup() != null ? request.getOptionGroup() : request.getServiceId();

        List<LeadProposal> created = new ArrayList<>();
        for (ProposalItemBatchLine line : request.getItems()) {
            LeadProposal item = LeadProposal.builder()
                    .id(UniqueIdResolver.resolve(leadProposalRepository::existsById))
                    .leadId(id)
                    .serviceId(request.getServiceId())
                    .serviceLabel(serviceLabel)
                    .type(line.getType())
                    .description(line.getDescription())
                    .supplier(line.getSupplier())
                    .netCost(line.getNetCost() != null ? line.getNetCost() : BigDecimal.ZERO)
                    .sellingPrice(line.getSellingPrice() != null ? line.getSellingPrice() : BigDecimal.ZERO)
                    .optionGroup(optionGroup)
                    .createdAt(LocalDateTime.now())
                    .createdBy(currentUserId())
                    .build();
            created.add(item);
        }
        leadProposalRepository.saveAll(created);
        recomputeQuotedTotals(lead);
        leadTimelineService.record(id, request.getServiceId(), LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
                created.size() + " proposal option(s) added" + (serviceLabel != null ? " to " + serviceLabel : ""));
        log.info("Proposal items batch-added: leadId={}, count={}, optionGroup={}", id, created.size(), optionGroup);
        List<String> ids = created.stream().map(LeadProposal::getId).toList();
        return leadProposalRepository.findAllById(ids).stream().map(this::toProposalItemResponse).toList();
    }

    /**
     * Agent-side "make this the default option" - the customer can override this later via the
     * public proposal selection endpoint, which stamps selectedBy=CUSTOMER instead.
     */
    @Transactional
    public ProposalItemResponse selectProposalItem(String id, String itemId) {
        Lead lead = findAccessibleLead(id);
        assertProposalUnlocked(lead);
        LeadProposal item = leadProposalRepository.findByIdAndLeadId(itemId, id)
                .orElseThrow(() -> new IllegalArgumentException("Proposal item not found: " + itemId));
        if (item.getOptionGroup() == null) {
            throw new IllegalArgumentException("Only an option line can be selected: " + itemId);
        }
        enforceSingleSelection(id, item.getOptionGroup(), itemId, "AGENT");
        recomputeQuotedTotals(lead);
        leadTimelineService.record(id, item.getServiceId(), LeadTimelineEventType.PROPOSAL_OPTION_SELECTED,
                "Selected option: " + item.getDescription());
        log.info("Proposal option selected: leadId={}, itemId={}", id, itemId);
        return toProposalItemResponse(leadProposalRepository.findByIdAndLeadId(itemId, id).orElse(item));
    }

    /**
     * The single write path that enforces "exactly one selected line per option group" -
     * every caller that sets is_selected=true goes through here rather than setting the flag
     * directly, so the invariant can never be bypassed by a partial update.
     */
    private void enforceSingleSelection(String leadId, String optionGroup, String selectedItemId, String selectedBy) {
        List<LeadProposal> siblings = leadProposalRepository.findByLeadId(leadId).stream()
                .filter(line -> optionGroup.equals(line.getOptionGroup()))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        for (LeadProposal sibling : siblings) {
            boolean isTarget = sibling.getId().equals(selectedItemId);
            sibling.setSelected(isTarget);
            if (isTarget) {
                sibling.setSelectedBy(selectedBy);
                sibling.setSelectedAt(now);
            }
        }
        leadProposalRepository.saveAll(siblings);
    }

    @Transactional
    public ProposalItemResponse updateProposalItem(String id, String itemId, ProposalItemUpdateRequest request) {
        Lead lead = findAccessibleLead(id);
        assertProposalUnlocked(lead);
        LeadProposal item = leadProposalRepository.findByIdAndLeadId(itemId, id)
                .orElseThrow(() -> new IllegalArgumentException("Proposal item not found: " + itemId));
        if (request.getDescription() != null) {
            item.setDescription(request.getDescription());
        }
        if (request.getSupplier() != null) {
            item.setSupplier(request.getSupplier());
        }
        if (request.getNetCost() != null) {
            item.setNetCost(request.getNetCost());
        }
        if (request.getSellingPrice() != null) {
            item.setSellingPrice(request.getSellingPrice());
        }
        leadProposalRepository.save(item);
        recomputeQuotedTotals(lead);
        leadTimelineService.record(id, item.getServiceId(), LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
                "Proposal item updated: " + item.getDescription());
        log.info("Proposal item updated: leadId={}, itemId={}", id, itemId);
        return toProposalItemResponse(item);
    }

    @Transactional
    public void removeProposalItem(String id, String itemId) {
        Lead lead = findAccessibleLead(id);
        assertProposalUnlocked(lead);
        LeadProposal item = leadProposalRepository.findByIdAndLeadId(itemId, id)
                .orElseThrow(() -> new IllegalArgumentException("Proposal item not found: " + itemId));
        leadProposalRepository.delete(item);
        recomputeQuotedTotals(lead);
        leadTimelineService.record(id, item.getServiceId(), LeadTimelineEventType.PROPOSAL_ITEM_REMOVED,
                "Proposal item removed: " + item.getDescription());
        log.info("Proposal item removed: leadId={}, itemId={}", id, itemId);
    }

    /**
     * A quoted line has no status of its own - lines belonging to a CANCELLED service are
     * excluded from every total, so this has to run through the owning service. Also refreshes
     * each affected service's own net/selling snapshot, since that is the only write path that
     * touches a lead's proposal lines.
     */
    private void recomputeQuotedTotals(Lead lead) {
        List<com.voyra.crm.entity.LeadService> services =
                leadServiceRepository.findByLeadIdOrderBySortOrderAsc(lead.getId());
        Map<String, ServiceStatus> statusByServiceId = services.stream()
                .collect(Collectors.toMap(com.voyra.crm.entity.LeadService::getId, com.voyra.crm.entity.LeadService::getStatus));
        Map<String, List<LeadProposal>> linesByService = leadProposalRepository.findByLeadId(lead.getId()).stream()
                .filter(line -> line.getServiceId() != null)
                .collect(Collectors.groupingBy(LeadProposal::getServiceId));

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal selling = BigDecimal.ZERO;
        for (LeadProposal line : leadProposalRepository.findByLeadId(lead.getId())) {
            ServiceStatus owningStatus = line.getServiceId() != null ? statusByServiceId.get(line.getServiceId()) : null;
            if (owningStatus == ServiceStatus.CANCELLED) {
                continue;
            }
            if (line.getOptionGroup() != null && !line.isSelected()) {
                continue;
            }
            net = net.add(line.getNetCost());
            selling = selling.add(line.getSellingPrice());
        }
        lead.setQuotedNetTotal(net);
        lead.setQuotedSellingTotal(selling);
        leadRepository.save(lead);

        for (com.voyra.crm.entity.LeadService service : services) {
            List<LeadProposal> lines = linesByService.getOrDefault(service.getId(), List.of()).stream()
                    .filter(line -> line.getOptionGroup() == null || line.isSelected())
                    .toList();
            service.setNetTotal(lines.stream().map(LeadProposal::getNetCost).reduce(BigDecimal.ZERO, BigDecimal::add));
            service.setSellingTotal(lines.stream().map(LeadProposal::getSellingPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        leadServiceRepository.saveAll(services);
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /**
     * Shared access check reused by ProposalLinkService, and by every lead-level mutation
     * below (status, trip details, notes, proposal items, traveller manifest). An agent
     * reaches a lead either because they created it, or because they are personally
     * assigned to - or manage the type of - at least one service on it: a Visa agent quoting
     * their own service via ServiceProposalTable, or reading the manifest a Visa checklist
     * needs, did not create this lead and must not be locked out.
     */
    public Lead findAccessibleLead(String id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !lead.getCreatedBy().equals(principal.userId())
                && !hasServiceAccess(id, principal.userId())) {
            throw new AccessDeniedException("This lead is not accessible to you");
        }
        return lead;
    }

    private boolean hasServiceAccess(String leadId, String agentId) {
        return com.voyra.crm.util.LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, agentId);
    }

    /**
     * Set true automatically once a customer confirms their option picks on the public
     * proposal, and checked by every proposal-item write (agent-side included) so nothing
     * can silently drift from what the customer saw and confirmed. Only an agent/owner can
     * clear it via {@link #setProposalLocked} - never the customer.
     */
    private void assertProposalUnlocked(Lead lead) {
        if (lead.isProposalLocked()) {
            throw new IllegalStateException("This proposal is locked. Unlock it before making changes.");
        }
    }

    /** In-app surfacing only - flags a lead for a senior's attention. No push, no email. */
    @Transactional
    public LeadDetailResponse setEscalated(String id, boolean escalated, String reason) {
        if (escalated && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required when escalating a lead");
        }
        Lead lead = findAccessibleLead(id);
        Map<String, String> before = AuditSnapshot.of(lead, AUDITED);
        lead.setEscalated(escalated);
        lead.setEscalationReason(escalated ? reason : null);
        lead.setEscalatedAt(escalated ? LocalDateTime.now() : null);
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(lead, AUDITED));
        leadRepository.save(lead);
        auditService.recordUpdate(AuditEntityType.LEAD, lead.getId(), lead.getClientName() + " / " + lead.getDestination(), changes);
        log.info("Lead escalation changed: leadId={}, escalated={}", id, escalated);
        return toDetailResponse(lead);
    }

    @Transactional
    public LeadDetailResponse setProposalLocked(String id, boolean locked) {
        Lead lead = findAccessibleLead(id);
        lead.setProposalLocked(locked);
        leadRepository.save(lead);
        leadTimelineService.record(id, locked ? LeadTimelineEventType.PROPOSAL_LOCKED : LeadTimelineEventType.PROPOSAL_UNLOCKED,
                locked ? "Proposal locked" : "Proposal unlocked");
        log.info("Proposal lock changed: leadId={}, locked={}", id, locked);
        return toDetailResponse(lead);
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
                .createdBy(lead.getCreatedBy()).createdByName(lead.getCreatedByName())
                .followUpDate(lead.getFollowUpDate()).createdAt(lead.getCreatedAt()).overdue(isOverdue(lead))
                .escalated(lead.getEscalated())
                .build();
    }

    private LeadDetailResponse toDetailResponse(Lead lead) {
        List<LeadProposal> items = leadProposalRepository.findByLeadId(lead.getId());
        List<ProposalItemResponse> itemResponses = items.stream().map(this::toProposalItemResponse).toList();

        // lead.quotedNetTotal/quotedSellingTotal are the authoritative roll-up, kept in sync by
        // recomputeQuotedTotals() on every proposal write and by ServiceInstanceService on every
        // service status change - both exclude lines whose owning service is cancelled.
        BigDecimal totalNet = lead.getQuotedNetTotal();
        BigDecimal totalSelling = lead.getQuotedSellingTotal();

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
                .source(lead.getSource()).priority(lead.getPriority()).createdBy(lead.getCreatedBy())
                .createdByName(lead.getCreatedByName()).followUpDate(lead.getFollowUpDate())
                .lostReason(lead.getLostReason()).leadDescription(lead.getLeadDescription())
                .preferences(lead.getPreferences())
                .specialNotes(lead.getSpecialNotes()).travelPreferences(lead.getTravelPreferences())
                .createdAt(lead.getCreatedAt()).updatedAt(lead.getUpdatedAt()).overdue(isOverdue(lead))
                .guestDetails(toGuestDetails(lead))
                .members(manifest)
                .manifestComplete(isManifestComplete(lead, manifest))
                .services(serviceInstanceService.listForLead(lead.getId()))
                .followUps(leadFollowUpService.listForLead(lead.getId()))
                .vouchers(List.of())
                .bookings(bookingService.listForLead(lead.getId()))
                .invoices(invoiceService.listForLead(lead.getId()))
                .proposalItems(itemResponses)
                .totalNetCost(totalNet)
                .totalSellingPrice(totalSelling)
                .marginPercent(MarginCalculator.marginPercent(totalNet, totalSelling))
                .notes(notes)
                .hasPublicProposalLink(lead.getPublicProposalToken() != null)
                .proposalLocked(lead.isProposalLocked())
                .escalated(lead.getEscalated()).escalatedAt(lead.getEscalatedAt())
                .escalationReason(lead.getEscalationReason())
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
                .id(item.getId()).serviceId(item.getServiceId()).serviceLabel(item.getServiceLabel())
                .type(item.getType()).description(item.getDescription())
                .supplier(item.getSupplier()).netCost(item.getNetCost()).sellingPrice(item.getSellingPrice())
                .marginPercent(MarginCalculator.marginPercent(item.getNetCost(), item.getSellingPrice()))
                .optionGroup(item.getOptionGroup()).selected(item.isSelected())
                .selectedBy(item.getSelectedBy()).selectedAt(item.getSelectedAt())
                .build();
    }
}
