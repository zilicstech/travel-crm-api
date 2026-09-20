package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.ServiceAssignRequest;
import com.voyra.crm.dto.ServiceDraft;
import com.voyra.crm.dto.ServicePreferenceToggleRequest;
import com.voyra.crm.dto.ServiceResponse;
import com.voyra.crm.dto.ServiceStatusUpdateRequest;
import com.voyra.crm.dto.VisaChecklistEntry;
import com.voyra.crm.dto.VisaChecklistToggleRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.ServiceDateRangeDeriver;
import com.voyra.crm.util.ServiceLabelDeriver;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lead_service spine: any number of Flight/Hotel/Visa/Transfer instances on a lead. Every
 * mutation here is one of the write obligations in docs/LLD_LEAD_MANAGEMENT.md §11 - each does
 * all of its listed side effects (label re-derivation across siblings, date re-derivation, the
 * lead-level roll-up, exactly one timeline row) in the same transaction, or a screen ends up
 * showing a stale number.
 *
 * <p>Injects {@link LeadRepository} directly rather than the {@code LeadService} bean - the two
 * classes share a name (one is this table's business logic, the other is the pre-existing lead
 * service) and nothing here needs the older class's methods.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceInstanceService {

    private static final Set<ServiceStatus> CLAIMABLE_BLOCKING_STATUSES =
            Set.of(ServiceStatus.CONFIRMED, ServiceStatus.BOOKED, ServiceStatus.CANCELLED);

    /** flightSectors and visaChecklists are deliberately excluded - nested JSON collections, not
     *  scalar fields AuditSnapshot's stringify formats meaningfully. */
    private static final String[] AUDITED = {
            "status", "assignedAgentId", "dueDate", "preferences",
            "flightTripType", "flightCabin",
            "hotelCity", "hotelCountryCode", "hotelCheckIn", "hotelCheckOut", "hotelNights", "hotelRooms",
            "visaSourceCity", "visaSourceCountry", "visaCountry", "visaIntendedTravelDate", "visaAppointmentDate",
            "transferVehicleType", "transferPickup", "transferDropoff", "transferDate", "transferTime", "transferPassengers"
    };

    private final LeadServiceRepository leadServiceRepository;
    private final LeadRepository leadRepository;
    private final LeadProposalRepository leadProposalRepository;
    private final AgentRepository agentRepository;
    private final LeadTimelineService leadTimelineService;
    private final AuditService auditService;

    // ---------------------------------------------------------------------
    // Create / read
    // ---------------------------------------------------------------------

    @Transactional
    public List<ServiceResponse> createServices(String leadId, List<ServiceDraft> drafts) {
        Lead lead = findAccessibleLead(leadId);
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Add at least one service");
        }

        List<LeadService> existing = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId);
        int nextSort = existing.stream().mapToInt(LeadService::getSortOrder).max().orElse(-1) + 1;

        List<LeadService> created = new ArrayList<>();
        for (ServiceDraft draft : drafts) {
            LeadService service = LeadService.builder()
                    .id(UniqueIdResolver.resolve(leadServiceRepository::existsById))
                    .leadId(leadId)
                    .clientId(lead.getClientId())
                    .clientName(lead.getClientName())
                    .leadDestination(lead.getDestination())
                    .leadStatus(lead.getStatus())
                    .type(draft.getType())
                    .status(ServiceStatus.NOT_STARTED)
                    .label(draft.getType().name())
                    .sortOrder(nextSort++)
                    .preferences(draft.getPreferences() != null ? draft.getPreferences() : List.of())
                    .dueDate(draft.getDueDate())
                    .flightTripType(draft.getFlightTripType())
                    .flightCabin(draft.getFlightCabin())
                    .flightSectors(draft.getFlightSectors() != null ? draft.getFlightSectors() : List.of())
                    .hotelCity(draft.getHotelCity())
                    .hotelCountryCode(draft.getHotelCountryCode())
                    .hotelCheckIn(draft.getHotelCheckIn())
                    .hotelCheckOut(draft.getHotelCheckOut())
                    .hotelNights(draft.getHotelNights())
                    .hotelRooms(draft.getHotelRooms())
                    .visaSourceCity(draft.getVisaSourceCity())
                    .visaSourceCountry(draft.getVisaSourceCountry())
                    .visaCountry(draft.getVisaCountry())
                    .visaIntendedTravelDate(draft.getVisaIntendedTravelDate())
                    .visaAppointmentDate(draft.getVisaAppointmentDate())
                    .visaChecklists(Map.of())
                    .transferVehicleType(draft.getTransferVehicleType())
                    .transferPickup(draft.getTransferPickup())
                    .transferDropoff(draft.getTransferDropoff())
                    .transferDate(draft.getTransferDate())
                    .transferTime(draft.getTransferTime())
                    .transferPassengers(draft.getTransferPassengers())
                    .createdAt(LocalDateTime.now())
                    .createdBy(currentUserId())
                    .build();
            ServiceDateRangeDeriver.derive(service);
            created.add(service);
        }

        existing.addAll(created);
        ServiceLabelDeriver.deriveAll(existing);
        leadServiceRepository.saveAll(existing);

        recomputeLeadRollups(lead, existing);
        leadRepository.save(lead);

        for (LeadService service : created) {
            leadTimelineService.record(leadId, service.getId(), LeadTimelineEventType.SERVICE_ADDED,
                    service.getLabel() + " added");
            auditService.recordCreate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel());
        }
        log.info("Services added to lead: leadId={}, count={}", leadId, created.size());

        return created.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> listForLead(String leadId) {
        return leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId).stream()
                .map(this::toResponse).toList();
    }

    /** GET /api/services - the cross-lead board, scoped to the caller's manageable service types (owner sees all). */
    @Transactional(readOnly = true)
    public List<ServiceResponse> board(ServiceType typeFilter, ServiceStatus statusFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<ServiceType> types;
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            types = agent.getManageableServices();
            if (typeFilter != null && !types.contains(typeFilter)) {
                return List.of();
            }
        } else {
            types = typeFilter != null ? List.of(typeFilter) : List.of(ServiceType.values());
        }
        if (typeFilter != null) {
            types = List.of(typeFilter);
        }
        List<LeadService> rows = statusFilter == null
                ? leadServiceRepository.findByTypeInOrderByCreatedAtDesc(types)
                : leadServiceRepository.findByTypeInAndStatusOrderByCreatedAtDesc(types, statusFilter);
        return rows.stream().map(this::toResponse).toList();
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResponse updateService(String leadId, String serviceId, ServiceDraft draft) {
        Lead lead = findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        assertEditAccess(service);
        Map<String, String> before = AuditSnapshot.of(service, AUDITED);

        service.setDueDate(draft.getDueDate());
        if (draft.getPreferences() != null) {
            service.setPreferences(draft.getPreferences());
        }
        service.setFlightTripType(draft.getFlightTripType());
        service.setFlightCabin(draft.getFlightCabin());
        if (draft.getFlightSectors() != null) {
            service.setFlightSectors(draft.getFlightSectors());
        }
        service.setHotelCity(draft.getHotelCity());
        service.setHotelCountryCode(draft.getHotelCountryCode());
        service.setHotelCheckIn(draft.getHotelCheckIn());
        service.setHotelCheckOut(draft.getHotelCheckOut());
        service.setHotelNights(draft.getHotelNights());
        service.setHotelRooms(draft.getHotelRooms());
        service.setVisaSourceCity(draft.getVisaSourceCity());
        service.setVisaSourceCountry(draft.getVisaSourceCountry());
        service.setVisaCountry(draft.getVisaCountry());
        service.setVisaIntendedTravelDate(draft.getVisaIntendedTravelDate());
        service.setVisaAppointmentDate(draft.getVisaAppointmentDate());
        service.setTransferVehicleType(draft.getTransferVehicleType());
        service.setTransferPickup(draft.getTransferPickup());
        service.setTransferDropoff(draft.getTransferDropoff());
        service.setTransferDate(draft.getTransferDate());
        service.setTransferTime(draft.getTransferTime());
        service.setTransferPassengers(draft.getTransferPassengers());
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());

        ServiceDateRangeDeriver.derive(service);

        List<LeadService> siblings = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId);
        ServiceLabelDeriver.deriveAll(siblings);
        leadServiceRepository.saveAll(siblings);

        recomputeLeadRollups(lead, siblings);
        leadRepository.save(lead);

        LeadService saved = siblings.stream().filter(s -> s.getId().equals(serviceId)).findFirst().orElse(service);
        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                saved.getLabel() + " updated");
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, saved.getId(), saved.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(saved, AUDITED)));
        log.info("Service updated: leadId={}, serviceId={}", leadId, serviceId);
        return toResponse(saved);
    }

    @Transactional
    public ServiceResponse setStatus(String leadId, String serviceId, ServiceStatusUpdateRequest request) {
        Lead lead = findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        boolean isOwner = !principal.isAgent();
        boolean isAssigned = service.getAssignedAgentId() != null
                && service.getAssignedAgentId().equals(principal.userId());
        if (!isOwner && !isAssigned) {
            throw new AccessDeniedException("Only the owner or the assigned agent can change this service's status");
        }
        if (service.getAssignedAgentId() == null) {
            throw new IllegalStateException("Assign this service before changing its status");
        }
        if (request.getStatus() == ServiceStatus.BOOKED) {
            throw new IllegalArgumentException(
                    "Booked is set automatically when the first booking is logged - it cannot be chosen by hand");
        }
        Map<String, String> before = AuditSnapshot.of(service, AUDITED);

        service.setStatus(request.getStatus());
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());
        leadServiceRepository.save(service);

        List<LeadService> all = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId);
        recomputeLeadRollups(lead, all);
        leadRepository.save(lead);

        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                service.getLabel() + " status changed to " + request.getStatus());
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(service, AUDITED)));
        log.info("Service status updated: leadId={}, serviceId={}, status={}", leadId, serviceId, request.getStatus());
        return toResponse(service);
    }

    /** An agent claims a service by accepting it - there is no owner-side "assign to agent" for the first claim. */
    @Transactional
    public ServiceResponse acceptService(String leadId, String serviceId) {
        findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgent()) {
            throw new IllegalStateException("Only an agent can accept a service");
        }
        Agent agent = agentRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
        if (!agent.getManageableServices().contains(service.getType())) {
            throw new AccessDeniedException("This agent does not manage " + service.getType());
        }
        if (service.getAssignedAgentId() != null || CLAIMABLE_BLOCKING_STATUSES.contains(service.getStatus())) {
            throw new IllegalStateException("This service is not open to be accepted");
        }
        Map<String, String> before = AuditSnapshot.of(service, AUDITED);

        service.setAssignedAgentId(agent.getId());
        service.setAssignedAgentName(agent.getName());
        service.setStatus(ServiceStatus.IN_PROGRESS);
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());
        leadServiceRepository.save(service);

        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                agent.getName() + " accepted " + service.getLabel());
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(service, AUDITED)));
        log.info("Service accepted: leadId={}, serviceId={}, agentId={}", leadId, serviceId, agent.getId());
        return toResponse(service);
    }

    /** Owner-only reassignment. An agent may only claim via accept, never hand off to someone else. */
    @Transactional
    public ServiceResponse assignService(String leadId, String serviceId, ServiceAssignRequest request) {
        findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Agent agent = agentRepository.findByIdAndTenantId(request.getAgentId(), principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + request.getAgentId()));
        Map<String, String> before = AuditSnapshot.of(service, AUDITED);

        service.setAssignedAgentId(agent.getId());
        service.setAssignedAgentName(agent.getName());
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());
        leadServiceRepository.save(service);

        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                service.getLabel() + " assigned to " + agent.getName());
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(service, AUDITED)));
        log.info("Service reassigned: leadId={}, serviceId={}, agentId={}", leadId, serviceId, agent.getId());
        return toResponse(service);
    }

    @Transactional
    public ServiceResponse togglePreference(String leadId, String serviceId, ServicePreferenceToggleRequest request) {
        findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        assertEditAccess(service);

        Map<String, String> before = AuditSnapshot.of(service, AUDITED);
        List<String> preferences = new ArrayList<>(
                service.getPreferences() != null ? service.getPreferences() : List.of());
        if (Boolean.TRUE.equals(request.getOn())) {
            if (!preferences.contains(request.getName())) {
                preferences.add(request.getName());
            }
        } else {
            preferences.remove(request.getName());
        }
        service.setPreferences(preferences);
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());
        leadServiceRepository.save(service);

        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                request.getName() + (Boolean.TRUE.equals(request.getOn()) ? " added to " : " removed from ")
                        + service.getLabel() + "'s preferences");
        auditService.recordUpdate(AuditEntityType.LEAD_SERVICE, service.getId(), service.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(service, AUDITED)));
        return toResponse(service);
    }

    @Transactional
    public ServiceResponse toggleVisaChecklist(String leadId, String serviceId, VisaChecklistToggleRequest request) {
        findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        assertEditAccess(service);
        if (service.getType() != ServiceType.VISA) {
            throw new IllegalArgumentException("Only a Visa service carries a visa checklist");
        }

        Map<String, VisaChecklistEntry> checklists = new HashMap<>(
                service.getVisaChecklists() != null ? service.getVisaChecklists() : Map.of());
        VisaChecklistEntry entry = checklists.getOrDefault(request.getMemberId(), VisaChecklistEntry.builder().build());
        VisaChecklistEntry updated = applyKey(entry, request.getKey(), request.getValue());
        checklists.put(request.getMemberId(), updated);
        service.setVisaChecklists(checklists);
        service.setUpdatedAt(LocalDateTime.now());
        service.setUpdatedBy(currentUserId());
        leadServiceRepository.save(service);

        leadTimelineService.record(leadId, serviceId, LeadTimelineEventType.SERVICE_UPDATED,
                "Visa checklist updated for a traveller on " + service.getLabel());
        return toResponse(service);
    }

    private VisaChecklistEntry applyKey(VisaChecklistEntry entry, String key, Boolean value) {
        VisaChecklistEntry.VisaChecklistEntryBuilder b = entry.toBuilder();
        return switch (key) {
            case "passportCollected" -> b.passportCollected(value).build();
            case "photosCollected" -> b.photosCollected(value).build();
            case "formsFilled" -> b.formsFilled(value).build();
            case "submittedToEmbassy" -> b.submittedToEmbassy(value).build();
            case "approved" -> b.approved(value).build();
            case "passportReturned" -> b.passportReturned(value).build();
            default -> throw new IllegalArgumentException("Unknown visa checklist key: " + key);
        };
    }

    @Transactional
    public void deleteService(String leadId, String serviceId) {
        Lead lead = findAccessibleLead(leadId);
        LeadService service = findServiceOnLead(leadId, serviceId);
        assertEditAccess(service);
        String label = service.getLabel();
        List<AuditChange> finalSnapshot = AuditSnapshot.asFullSnapshot(AuditSnapshot.of(service, AUDITED));

        leadServiceRepository.delete(service);

        List<LeadService> remaining = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId);
        ServiceLabelDeriver.deriveAll(remaining);
        leadServiceRepository.saveAll(remaining);

        recomputeLeadRollups(lead, remaining);
        leadRepository.save(lead);

        leadTimelineService.record(leadId, LeadTimelineEventType.SERVICE_REMOVED, label + " removed");
        auditService.recordDelete(AuditEntityType.LEAD_SERVICE, serviceId, label, finalSnapshot);
        log.info("Service removed: leadId={}, serviceId={}", leadId, serviceId);
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /**
     * Existence check only - NOT an authorization gate. Who may act on a given service is
     * decided per-service by {@link #assertEditAccess}, which every mutation here calls right
     * after this: an agent's claim to a service comes from its type or from being personally
     * assigned to it, never from owning the lead as a whole. Gating here on lead ownership
     * (as an earlier version of this method did) silently broke every cross-lead "My Desk"
     * flow - an agent could never accept, update or requote a service on a lead owned by
     * someone else, which defeats the entire point of manageableServices.
     */
    private Lead findAccessibleLead(String id) {
        return leadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + id));
    }

    private LeadService findServiceOnLead(String leadId, String serviceId) {
        LeadService service = leadServiceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found: " + serviceId));
        if (!service.getLeadId().equals(leadId)) {
            throw new IllegalArgumentException("Service " + serviceId + " does not belong to lead " + leadId);
        }
        return service;
    }

    /** Owner, or an agent whose manageableServices covers this type, or the agent already on the job. */
    /** Package-private - also called from BookingService so logging a booking on a service
     *  goes through the exact same gate as every other mutation to that service. */
    void assertEditAccess(LeadService service) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgent()) {
            return;
        }
        if (service.getAssignedAgentId() != null && service.getAssignedAgentId().equals(principal.userId())) {
            return;
        }
        Agent agent = agentRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
        if (!agent.getManageableServices().contains(service.getType())) {
            throw new AccessDeniedException("Only agents who handle " + service.getType() + " can update this service");
        }
    }

    /**
     * Widens the lead's travel window and quoted totals from every non-cancelled service, and
     * refreshes service_count. Cancelled services are excluded from the window - it is a
     * commitment - but stay in the Trip Schedule, which is a record; that asymmetry lives in the
     * schedule read path, not here.
     */
    private void recomputeLeadRollups(Lead lead, List<LeadService> allServicesOnLead) {
        List<LeadService> active = allServicesOnLead.stream()
                .filter(s -> s.getStatus() != ServiceStatus.CANCELLED)
                .toList();

        LocalDate from = active.stream().map(LeadService::getDateFrom)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(lead.getTravelDateFrom());
        LocalDate to = active.stream().map(LeadService::getDateTo)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(lead.getTravelDateTo());
        lead.setTravelDateFrom(from);
        lead.setTravelDateTo(to);
        lead.setServiceCount(allServicesOnLead.size());

        Set<String> cancelledServiceIds = allServicesOnLead.stream()
                .filter(s -> s.getStatus() == ServiceStatus.CANCELLED)
                .map(LeadService::getId).collect(Collectors.toSet());
        List<LeadProposal> lines = leadProposalRepository.findByLeadId(lead.getId());
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal selling = BigDecimal.ZERO;
        for (LeadProposal line : lines) {
            if (line.getServiceId() != null && cancelledServiceIds.contains(line.getServiceId())) {
                continue;
            }
            net = net.add(line.getNetCost());
            selling = selling.add(line.getSellingPrice());
        }
        lead.setQuotedNetTotal(net);
        lead.setQuotedSellingTotal(selling);
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private ServiceResponse toResponse(LeadService s) {
        return ServiceResponse.builder()
                .id(s.getId()).leadId(s.getLeadId()).clientName(s.getClientName())
                .leadDestination(s.getLeadDestination()).leadStatus(s.getLeadStatus())
                .type(s.getType()).status(s.getStatus()).label(s.getLabel()).sortOrder(s.getSortOrder())
                .assignedAgentId(s.getAssignedAgentId()).assignedAgentName(s.getAssignedAgentName())
                .preferences(s.getPreferences()).dueDate(s.getDueDate())
                .dateFrom(s.getDateFrom()).dateTo(s.getDateTo())
                .netTotal(s.getNetTotal()).sellingTotal(s.getSellingTotal())
                .flightTripType(s.getFlightTripType()).flightCabin(s.getFlightCabin())
                .flightSectors(s.getFlightSectors())
                .hotelCity(s.getHotelCity()).hotelCountryCode(s.getHotelCountryCode())
                .hotelCheckIn(s.getHotelCheckIn()).hotelCheckOut(s.getHotelCheckOut())
                .hotelNights(s.getHotelNights()).hotelRooms(s.getHotelRooms())
                .visaSourceCity(s.getVisaSourceCity()).visaSourceCountry(s.getVisaSourceCountry())
                .visaCountry(s.getVisaCountry()).visaIntendedTravelDate(s.getVisaIntendedTravelDate())
                .visaAppointmentDate(s.getVisaAppointmentDate()).visaChecklists(s.getVisaChecklists())
                .transferVehicleType(s.getTransferVehicleType()).transferPickup(s.getTransferPickup())
                .transferDropoff(s.getTransferDropoff()).transferDate(s.getTransferDate())
                .transferTime(s.getTransferTime()).transferPassengers(s.getTransferPassengers())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }
}
