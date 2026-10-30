package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.FlightSectorDto;
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
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Covers every branch of {@link ServiceInstanceService} the accept / reassign tests leave open. */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceServiceCoverageTest {

    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuditService auditService;
    @InjectMocks private ServiceInstanceService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private Lead lead() {
        return Lead.builder().id("L1").clientId("C1").clientName("Client One").destination("Bali")
                .status(LeadStatus.NEW)
                .travelDateFrom(LocalDate.of(2026, 1, 1)).travelDateTo(LocalDate.of(2026, 1, 9)).build();
    }

    private LeadService svc(String id, ServiceType type, ServiceStatus status) {
        return LeadService.builder().id(id).leadId("L1").type(type).status(status).label(type.name()).build();
    }

    private void leadAndService(LeadService s) {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(leadServiceRepository.findById(s.getId())).thenReturn(Optional.of(s));
    }

    private static Agent agent(String id, ServiceType... types) {
        return Agent.builder().id(id).name("Agent " + id).isActive(true).manageableServices(List.of(types)).build();
    }

    // ---------------------------------------------------------------- create / list / board

    @Test
    void refusesToCreateWithNoDrafts() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        assertThatThrownBy(() -> service.createServices("L1", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Add at least one service");
        assertThatThrownBy(() -> service.createServices("L1", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Add at least one service");
        verify(leadServiceRepository, never()).saveAll(anyList());
    }

    @Test
    void refusesAnUnknownLead() {
        when(leadRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createServices("NOPE", List.of(new ServiceDraft())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: NOPE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsServicesAfterTheExistingOnesAndRollsUpTheLead() {
        as("O1", UserType.AGENCY_OWNER);
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService existing = svc("S0", ServiceType.HOTEL, ServiceStatus.CANCELLED);
        existing.setSortOrder(4);
        existing.setDateFrom(LocalDate.of(2025, 12, 1));
        existing.setDateTo(LocalDate.of(2027, 1, 1));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(new ArrayList<>(List.of(existing)));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                LeadProposal.builder().serviceId("S0").netCost(new BigDecimal("500")).sellingPrice(new BigDecimal("600")).build(),
                LeadProposal.builder().serviceId(null).netCost(new BigDecimal("100.50")).sellingPrice(new BigDecimal("120.25")).build(),
                LeadProposal.builder().serviceId("OTHER").netCost(new BigDecimal("10")).sellingPrice(new BigDecimal("15")).build()));

        ServiceDraft flight = new ServiceDraft();
        flight.setType(ServiceType.FLIGHT);
        flight.setFlightSectors(List.of(sector("BOM", "DPS", LocalDate.of(2026, 2, 1)),
                sector("DPS", "BOM", LocalDate.of(2026, 2, 8))));
        ServiceDraft visa = new ServiceDraft();
        visa.setType(ServiceType.VISA);
        visa.setPreferences(List.of("Express"));
        visa.setVisaIntendedTravelDate(LocalDate.of(2026, 2, 3));

        List<ServiceResponse> out = service.createServices("L1", List.of(flight, visa));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getSortOrder()).isEqualTo(5);
        assertThat(out.get(1).getSortOrder()).isEqualTo(6);
        assertThat(out.get(0).getStatus()).isEqualTo(ServiceStatus.NOT_STARTED);
        assertThat(out.get(0).getClientName()).isEqualTo("Client One");
        assertThat(out.get(0).getPreferences()).isEmpty();
        assertThat(out.get(1).getPreferences()).containsExactly("Express");
        assertThat(out.get(0).getDateFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(out.get(0).getDateTo()).isEqualTo(LocalDate.of(2026, 2, 8));
        // the cancelled hotel's wide dates and its proposal line are left out of the roll-up
        assertThat(lead.getTravelDateFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(lead.getTravelDateTo()).isEqualTo(LocalDate.of(2026, 2, 8));
        assertThat(lead.getServiceCount()).isEqualTo(3);
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("110.50");
        assertThat(lead.getQuotedSellingTotal()).isEqualByComparingTo("135.25");
        ArgumentCaptor<List<LeadService>> saved = ArgumentCaptor.forClass(List.class);
        verify(leadServiceRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(3);
        verify(leadRepository).save(lead);
        verify(leadTimelineService).record(eq("L1"), eq(out.get(0).getId()), eq(LeadTimelineEventType.SERVICE_ADDED), eq("Flight added"));
        verify(auditService).recordCreate(AuditEntityType.LEAD_SERVICE, out.get(1).getId(), "Visa");
    }

    @Test
    void createKeepsTheLeadWindowWhenNoServiceHasDatesAndRejectsBadTrips() {
        as("O1", UserType.AGENCY_OWNER);
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(new ArrayList<>());
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of());
        ServiceDraft transfer = new ServiceDraft();
        transfer.setType(ServiceType.TRANSFER);

        List<ServiceResponse> out = service.createServices("L1", List.of(transfer));

        assertThat(out.get(0).getSortOrder()).isZero();
        assertThat(lead.getTravelDateFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(lead.getTravelDateTo()).isEqualTo(LocalDate.of(2026, 1, 9));
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("0");

        ServiceDraft badHotel = new ServiceDraft();
        badHotel.setType(ServiceType.HOTEL);
        badHotel.setHotelRooms(0);
        assertThatThrownBy(() -> service.createServices("L1", List.of(badHotel)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Rooms must be at least 1");
    }

    private static FlightSectorDto sector(String from, String to, LocalDate date) {
        FlightSectorDto d = new FlightSectorDto();
        d.setFrom(from);
        d.setTo(to);
        d.setDate(date);
        return d;
    }

    @Test
    void listsServicesForOneLeadAndGroupsThemForMany() {
        LeadService a = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        LeadService b = svc("S2", ServiceType.HOTEL, ServiceStatus.NOT_STARTED);
        b.setLeadId("L2");
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(a));
        when(leadServiceRepository.findByLeadIdInOrderBySortOrderAsc(List.of("L1", "L2"))).thenReturn(List.of(a, b));

        assertThat(service.listForLead("L1")).extracting(ServiceResponse::getId).containsExactly("S1");
        Map<String, List<ServiceResponse>> grouped = service.listForLeads(List.of("L1", "L2"));
        assertThat(grouped.keySet()).containsExactlyInAnyOrder("L1", "L2");
        assertThat(grouped.get("L2").get(0).getId()).isEqualTo("S2");
        assertThat(service.listForLeads(List.of())).isEmpty();
        verify(leadServiceRepository, never()).findByLeadIdInOrderBySortOrderAsc(List.of());
    }

    @Test
    void boardForAnAgentWithoutAnAgentRowFails() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.board(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
    }

    @Test
    void boardForAnAgentFilteringOnATypeTheyDoNotManageIsEmpty() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", ServiceType.FLIGHT)));
        assertThat(service.board(ServiceType.VISA, null)).isEmpty();
        verifyNoInteractions(leadServiceRepository);
    }

    @Test
    void boardForAnAgentShowsOnlyTheirTypes() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", ServiceType.FLIGHT, ServiceType.HOTEL)));
        LeadService row = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        when(leadServiceRepository.findByTypeInOrderByCreatedAtDesc(List.of(ServiceType.FLIGHT, ServiceType.HOTEL)))
                .thenReturn(List.of(row));
        when(leadServiceRepository.findByTypeInAndStatusOrderByCreatedAtDesc(List.of(ServiceType.FLIGHT), ServiceStatus.IN_PROGRESS))
                .thenReturn(List.of(row));

        assertThat(service.board(null, null)).extracting(ServiceResponse::getId).containsExactly("S1");
        assertThat(service.board(ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS)).hasSize(1);
    }

    @Test
    void boardForTheOwnerCoversEveryTypeUnlessFiltered() {
        as("O1", UserType.AGENCY_OWNER);
        when(leadServiceRepository.findByTypeInOrderByCreatedAtDesc(List.of(ServiceType.values()))).thenReturn(List.of());
        when(leadServiceRepository.findByTypeInOrderByCreatedAtDesc(List.of(ServiceType.VISA)))
                .thenReturn(List.of(svc("S9", ServiceType.VISA, ServiceStatus.NOT_STARTED)));

        assertThat(service.board(null, null)).isEmpty();
        assertThat(service.board(ServiceType.VISA, null)).extracting(ServiceResponse::getId).containsExactly("S9");
        verifyNoInteractions(agentRepository);
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateCopiesEveryFieldRelabelsSiblingsAndAudits() {
        as("O1", UserType.AGENCY_OWNER);
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService hotel = svc("S1", ServiceType.HOTEL, ServiceStatus.IN_PROGRESS);
        hotel.setPreferences(List.of("Old"));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(hotel));
        LeadService sibling = svc("S2", ServiceType.HOTEL, ServiceStatus.NOT_STARTED);
        sibling.setHotelCity("Ubud");
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(hotel, sibling));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of());

        ServiceDraft d = new ServiceDraft();
        d.setType(ServiceType.HOTEL);
        d.setHotelCity("Seminyak");
        d.setHotelCheckIn(LocalDate.of(2026, 3, 1));
        d.setHotelNights(3);
        d.setPreferences(List.of("Pool"));
        d.setFlightSectors(List.of());

        ServiceResponse out = service.updateService("L1", "S1", d);

        assertThat(out.getLabel()).isEqualTo("Hotel — Seminyak");
        assertThat(sibling.getLabel()).isEqualTo("Hotel — Ubud");
        assertThat(out.getPreferences()).containsExactly("Pool");
        assertThat(out.getDateTo()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(lead.getTravelDateFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(hotel.getUpdatedBy()).isEqualTo("O1");
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "Hotel — Seminyak updated");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(eq(AuditEntityType.LEAD_SERVICE), eq("S1"), eq("Hotel — Seminyak"), changes.capture());
        assertThat(changes.getValue()).extracting(AuditChange::getField).contains("hotelCity", "preferences");
    }

    @Test
    void updateKeepsPreferencesAndSectorsWhenTheDraftOmitsThemAndFallsBackToTheLoadedRow() {
        as("O1", UserType.AGENCY_OWNER);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService flight = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        flight.setPreferences(List.of("Aisle"));
        flight.setFlightSectors(List.of(sector("BOM", "DXB", LocalDate.of(2026, 4, 1))));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flight));
        // the sibling query no longer returns the row (a concurrent delete) - the loaded row answers
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of());
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of());

        ServiceResponse out = service.updateService("L1", "S1", new ServiceDraft());

        assertThat(out.getId()).isEqualTo("S1");
        assertThat(out.getPreferences()).containsExactly("Aisle");
        assertThat(out.getFlightSectors()).hasSize(1);
        assertThat(out.getDateFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void updateRefusesAServiceOnAnotherLeadOrMissing() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService other = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        other.setLeadId("L2");
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(other));
        when(leadServiceRepository.findById("S404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateService("L1", "S1", new ServiceDraft()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service S1 does not belong to lead L1");
        assertThatThrownBy(() -> service.updateService("L1", "S404", new ServiceDraft()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service not found: S404");
    }

    // ---------------------------------------------------------------- edit gate

    @Test
    void editGateLetsTheOwnerAndTheAssignedAgentThroughWithoutAnAgentLookup() {
        LeadService s = svc("S1", ServiceType.VISA, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A1");
        as("O1", UserType.AGENCY_OWNER);
        service.assertEditAccess(s);
        as("A1", UserType.AGENT);
        service.assertEditAccess(s);
        verifyNoInteractions(agentRepository);
    }

    @Test
    void editGateChecksTheAgentsTypes() {
        LeadService s = svc("S1", ServiceType.VISA, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A9");
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", ServiceType.VISA)));
        service.assertEditAccess(s);

        s.setAssignedAgentId(null);
        as("A2", UserType.AGENT);
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent("A2", ServiceType.FLIGHT)));
        assertThatThrownBy(() -> service.assertEditAccess(s))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only agents who handle VISA can update this service");

        as("A3", UserType.AGENT);
        when(agentRepository.findById("A3")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assertEditAccess(s))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A3");
    }

    // ---------------------------------------------------------------- status

    @Test
    void anAgentNotOnTheServiceCannotChangeItsStatus() {
        as("A2", UserType.AGENT);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A1");
        leadAndService(s);
        assertThatThrownBy(() -> service.setStatus("L1", "S1", status(ServiceStatus.CONFIRMED)))
                .isInstanceOf(AccessDeniedException.class);
        LeadService unassigned = svc("S2", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        when(leadServiceRepository.findById("S2")).thenReturn(Optional.of(unassigned));
        assertThatThrownBy(() -> service.setStatus("L1", "S2", status(ServiceStatus.CONFIRMED)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void statusNeedsAnAssigneeAndNeverBookedByHand() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService unassigned = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        leadAndService(unassigned);
        assertThatThrownBy(() -> service.setStatus("L1", "S1", status(ServiceStatus.CONFIRMED)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Assign this service before changing its status");

        unassigned.setAssignedAgentId("A1");
        assertThatThrownBy(() -> service.setStatus("L1", "S1", status(ServiceStatus.BOOKED)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be chosen by hand");

        unassigned.setStatus(ServiceStatus.BOOKED);
        assertThatThrownBy(() -> service.setStatus("L1", "S1", status(ServiceStatus.CONFIRMED)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancel the booking");
        verify(leadServiceRepository, never()).save(any());
    }

    @Test
    void theAssignedAgentCancellingAServiceDropsItFromTheLeadTotals() {
        as("A1", UserType.AGENT);
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A1");
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(s));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(s));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                LeadProposal.builder().serviceId("S1").netCost(new BigDecimal("900")).sellingPrice(new BigDecimal("1000")).build()));

        ServiceResponse out = service.setStatus("L1", "S1", status(ServiceStatus.CANCELLED));

        assertThat(out.getStatus()).isEqualTo(ServiceStatus.CANCELLED);
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("0");
        assertThat(lead.getQuotedSellingTotal()).isEqualByComparingTo("0");
        assertThat(lead.getServiceCount()).isEqualTo(1);
        verify(leadServiceRepository).save(s);
        verify(leadRepository).save(lead);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "FLIGHT status changed to CANCELLED");
    }

    private static ServiceStatusUpdateRequest status(ServiceStatus st) {
        ServiceStatusUpdateRequest r = new ServiceStatusUpdateRequest();
        r.setStatus(st);
        return r;
    }

    // ---------------------------------------------------------------- accept / assign / release

    @Test
    void onlyAnAgentWithAnAgentRowCanAccept() {
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        leadAndService(s);
        as("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> service.acceptService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Only an agent can accept a service");
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acceptService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
    }

    @Test
    void acceptWritesTheTimelineAndAudit() {
        as("A1", UserType.AGENT);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        leadAndService(s);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", ServiceType.FLIGHT)));

        ServiceResponse out = service.acceptService("L1", "S1");

        assertThat(out.getAssignedAgentName()).isEqualTo("Agent A1");
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "Agent A1 accepted FLIGHT");
        verify(auditService).recordUpdate(eq(AuditEntityType.LEAD_SERVICE), eq("S1"), eq("FLIGHT"), anyList());
    }

    @Test
    void assignRefusesAMissingAgentOneWithNoTypesAnInactiveOneAndTheCurrentAssignee() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A3");
        leadAndService(s);
        when(agentRepository.findByIdAndTenantId("A0", "T1")).thenReturn(Optional.empty());
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(
                Agent.builder().id("A1").name("NoTypes").isActive(true).manageableServices(null).build()));
        Agent inactive = agent("A2", ServiceType.FLIGHT);
        inactive.setIsActive(false);
        when(agentRepository.findByIdAndTenantId("A2", "T1")).thenReturn(Optional.of(inactive));
        when(agentRepository.findByIdAndTenantId("A3", "T1")).thenReturn(Optional.of(agent("A3", ServiceType.FLIGHT)));

        assertThatThrownBy(() -> service.assignService("L1", "S1", assign("A0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found in this agency: A0");
        assertThatThrownBy(() -> service.assignService("L1", "S1", assign("A1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NoTypes doesn't handle FLIGHT");
        assertThatThrownBy(() -> service.assignService("L1", "S1", assign("A2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent A2 is inactive - pick an active agent");
        assertThatThrownBy(() -> service.assignService("L1", "S1", assign("A3")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent A3 already has this service");
        verify(leadServiceRepository, never()).save(any());
    }

    @Test
    void assigningAnUnassignedServiceRecordsAnAssignmentNotAReassignment() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.NOT_STARTED);
        s.setAssignedAgentName("stale name");
        leadAndService(s);
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(agent("A1", ServiceType.FLIGHT)));

        ServiceResponse out = service.assignService("L1", "S1", assign("A1"));

        assertThat(out.getAssignedAgentId()).isEqualTo("A1");
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "FLIGHT assigned to Agent A1");
        verify(leadTimelineService, never()).record(any(), any(), eq(LeadTimelineEventType.SERVICE_REASSIGNED), any());
    }

    @Test
    void reassigningNamesBothAgents() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A9");
        s.setAssignedAgentName("Old Agent");
        leadAndService(s);
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(agent("A1", ServiceType.FLIGHT)));

        service.assignService("L1", "S1", assign("A1"));

        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_REASSIGNED,
                "FLIGHT reassigned from Old Agent to Agent A1");
    }

    private static ServiceAssignRequest assign(String agentId) {
        ServiceAssignRequest r = new ServiceAssignRequest();
        r.setAgentId(agentId);
        return r;
    }

    @Test
    void onlyTheAssignedAgentMayReleaseAndNotOnceSettled() {
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.CONFIRMED);
        s.setAssignedAgentId("A1");
        leadAndService(s);
        as("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> service.releaseService("L1", "S1")).isInstanceOf(AccessDeniedException.class);
        as("A2", UserType.AGENT);
        assertThatThrownBy(() -> service.releaseService("L1", "S1")).isInstanceOf(AccessDeniedException.class);
        as("A1", UserType.AGENT);
        assertThatThrownBy(() -> service.releaseService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("A CONFIRMED service can't be released");
        verify(leadServiceRepository, never()).save(any());
    }

    @Test
    void releaseClearsTheAgentAndResetsTheStatus() {
        as("A1", UserType.AGENT);
        LeadService s = svc("S1", ServiceType.HOTEL, ServiceStatus.IN_PROGRESS);
        s.setAssignedAgentId("A1");
        s.setAssignedAgentName("Agent A1");
        leadAndService(s);

        ServiceResponse out = service.releaseService("L1", "S1");

        assertThat(out.getAssignedAgentId()).isNull();
        assertThat(out.getAssignedAgentName()).isNull();
        assertThat(out.getStatus()).isEqualTo(ServiceStatus.NOT_STARTED);
        verify(leadServiceRepository).save(s);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_RELEASED, "Agent A1 released HOTEL");
    }

    // ---------------------------------------------------------------- preferences / visa checklist

    @Test
    void togglingAPreferenceOnAddsItOnceAndOffRemovesIt() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService s = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        s.setPreferences(null);
        leadAndService(s);

        assertThat(service.togglePreference("L1", "S1", pref("Window", true)).getPreferences()).containsExactly("Window");
        assertThat(service.togglePreference("L1", "S1", pref("Window", true)).getPreferences()).containsExactly("Window");
        assertThat(service.togglePreference("L1", "S1", pref("Window", false)).getPreferences()).isEmpty();

        verify(leadTimelineService, org.mockito.Mockito.times(2))
                .record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "Window added to FLIGHT's preferences");
        verify(leadTimelineService)
                .record("L1", "S1", LeadTimelineEventType.SERVICE_UPDATED, "Window removed from FLIGHT's preferences");
    }

    private static ServicePreferenceToggleRequest pref(String name, boolean on) {
        ServicePreferenceToggleRequest r = new ServicePreferenceToggleRequest();
        r.setName(name);
        r.setOn(on);
        return r;
    }

    @Test
    void visaChecklistOnlyOnAVisaServiceAndOnlyForATraveller() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService flight = svc("S1", ServiceType.FLIGHT, ServiceStatus.IN_PROGRESS);
        leadAndService(flight);
        assertThatThrownBy(() -> service.toggleVisaChecklist("L1", "S1", visa("M1", "approved", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only a Visa service carries a visa checklist");

        LeadService visa = svc("S2", ServiceType.VISA, ServiceStatus.IN_PROGRESS);
        when(leadServiceRepository.findById("S2")).thenReturn(Optional.of(visa));
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M9")).thenReturn(false);
        assertThatThrownBy(() -> service.toggleVisaChecklist("L1", "S2", visa("M9", "approved", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("That person isn't a traveller on this lead");
        verify(leadServiceRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"passportCollected", "photosCollected", "formsFilled", "submittedToEmbassy", "approved", "passportReturned"})
    void eachVisaChecklistKeySetsOnlyItsOwnStep(String key) {
        as("O1", UserType.AGENCY_OWNER);
        LeadService visa = svc("S2", ServiceType.VISA, ServiceStatus.IN_PROGRESS);
        visa.setVisaChecklists(null);
        leadAndService(visa);
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M1")).thenReturn(true);

        ServiceResponse out = service.toggleVisaChecklist("L1", "S2", visa("M1", key, true));

        VisaChecklistEntry e = out.getVisaChecklists().get("M1");
        Map<String, Boolean> steps = new HashMap<>(Map.of(
                "passportCollected", e.getPassportCollected(), "photosCollected", e.getPhotosCollected(),
                "formsFilled", e.getFormsFilled(), "submittedToEmbassy", e.getSubmittedToEmbassy(),
                "approved", e.getApproved(), "passportReturned", e.getPassportReturned()));
        assertThat(steps.remove(key)).isTrue();
        assertThat(steps.values()).containsOnly(false);
        verify(leadServiceRepository).save(visa);
        verify(leadTimelineService).record("L1", "S2", LeadTimelineEventType.SERVICE_UPDATED,
                "Visa checklist updated for a traveller on VISA");
    }

    @Test
    void anExistingChecklistEntryIsMergedAndAnUnknownKeyIsRefused() {
        as("O1", UserType.AGENCY_OWNER);
        LeadService visa = svc("S2", ServiceType.VISA, ServiceStatus.IN_PROGRESS);
        visa.setVisaChecklists(Map.of("M1", VisaChecklistEntry.builder().passportCollected(true).build()));
        leadAndService(visa);
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M1")).thenReturn(true);

        VisaChecklistEntry e = service.toggleVisaChecklist("L1", "S2", visa("M1", "approved", true)).getVisaChecklists().get("M1");
        assertThat(e.getPassportCollected()).isTrue();
        assertThat(e.getApproved()).isTrue();

        assertThatThrownBy(() -> service.toggleVisaChecklist("L1", "S2", visa("M1", "stamped", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown visa checklist key: stamped");
    }

    private static VisaChecklistToggleRequest visa(String memberId, String key, boolean value) {
        VisaChecklistToggleRequest r = new VisaChecklistToggleRequest();
        r.setMemberId(memberId);
        r.setKey(key);
        r.setValue(value);
        return r;
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteRelabelsTheRemainderRollsUpAndAuditsAFullSnapshot() {
        as("O1", UserType.AGENCY_OWNER);
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService doomed = svc("S1", ServiceType.HOTEL, ServiceStatus.NOT_STARTED);
        doomed.setLabel("Hotel — Ubud");
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(doomed));
        LeadService left = svc("S2", ServiceType.HOTEL, ServiceStatus.NOT_STARTED);
        left.setLabel("Hotel — Kuta");
        left.setDateFrom(LocalDate.of(2026, 5, 1));
        left.setDateTo(LocalDate.of(2026, 5, 3));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(left));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of());

        service.deleteService("L1", "S1");

        verify(leadServiceRepository).delete(doomed);
        assertThat(left.getLabel()).isEqualTo("Hotel");
        assertThat(lead.getServiceCount()).isEqualTo(1);
        assertThat(lead.getTravelDateFrom()).isEqualTo(LocalDate.of(2026, 5, 1));
        verify(leadTimelineService).record("L1", LeadTimelineEventType.SERVICE_REMOVED, "Hotel — Ubud removed");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditChange>> snap = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordDelete(eq(AuditEntityType.LEAD_SERVICE), eq("S1"), eq("Hotel — Ubud"), snap.capture());
        assertThat(snap.getValue()).extracting(AuditChange::getField).contains("status");
        assertThat(Set.copyOf(snap.getValue())).isNotEmpty();
    }
}
