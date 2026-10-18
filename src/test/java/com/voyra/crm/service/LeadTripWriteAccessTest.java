package com.voyra.crm.service;

import com.voyra.crm.dto.LeadDetailUpdateRequest;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.ProposalItemBatchCreateRequest;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trip-level writes (details, status, escalation, proposal lock, trip-level proposal lines) need
 * more than read access: the owner, the lead's creator, or an agent assigned to a service on it.
 * An agent who only manages a service type on the lead can read it but not change the trip.
 */
@ExtendWith(MockitoExtension.class)
class LeadTripWriteAccessTest {

    @Mock private LeadRepository leadRepository;
    @Mock private LeadNoteRepository leadNoteRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ClientService clientService;
    @Mock private MemberService memberService;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuthorResolver authorResolver;
    @Mock private ServiceInstanceService serviceInstanceService;
    @Mock private LeadFollowUpService leadFollowUpService;
    @Mock private BookingService bookingService;
    @Mock private InvoiceService invoiceService;
    @Mock private AuditService auditService;
    @InjectMocks private LeadService leadService;

    private final Lead lead = Lead.builder().id("L1").createdBy("CREATOR").status(LeadStatus.NEW)
            .adults(1).kids(0).build();
    // Flight is held by Vikram; Rahul handles Flight but holds nothing on this lead.
    private final com.voyra.crm.entity.LeadService flight = com.voyra.crm.entity.LeadService.builder()
            .id("S1").leadId("L1").type(ServiceType.FLIGHT).status(ServiceStatus.IN_PROGRESS).assignedAgentId("VIKRAM").build();
    private final LeadProposal tripLine = LeadProposal.builder().id("P1").leadId("L1")
            .description("Trip insurance").netCost(BigDecimal.ONE).sellingPrice(BigDecimal.ONE).build();

    @BeforeEach
    void setUp() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        lenient().when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(flight));
        lenient().when(agentRepository.findById("RAHUL")).thenReturn(Optional.of(
                Agent.builder().id("RAHUL").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        lenient().when(agentRepository.findById("VIKRAM")).thenReturn(Optional.of(
                Agent.builder().id("VIKRAM").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        lenient().when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(tripLine));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private ProposalItemCreateRequest tripLevelLine() {
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setType(ProposalItemType.MISCELLANEOUS);
        r.setDescription("Trip insurance");
        r.setNetCost(BigDecimal.TEN);
        r.setSellingPrice(BigDecimal.TEN);
        return r;
    }

    private List<Executable> tripWrites() {
        LeadStatusUpdateRequest status = new LeadStatusUpdateRequest();
        status.setStatus(LeadStatus.LOST);
        status.setLostReason("Too expensive");
        LeadDetailUpdateRequest details = new LeadDetailUpdateRequest();
        details.setDestination("Paris");
        ProposalItemBatchCreateRequest batch = new ProposalItemBatchCreateRequest();
        batch.setItems(List.of());
        return List.of(
                () -> leadService.updateStatus("L1", status),
                () -> leadService.updateDetails("L1", details),
                () -> leadService.setEscalated("L1", true, "Angry customer"),
                () -> leadService.setProposalLocked("L1", false),
                () -> leadService.addProposalItem("L1", tripLevelLine()),
                () -> leadService.addProposalItemsBatch("L1", batch),
                () -> leadService.updateProposalItem("L1", "P1", new ProposalItemUpdateRequest()),
                () -> leadService.removeProposalItem("L1", "P1"));
    }

    @Test
    void anAgentWhoOnlyReadsTheLeadCannotChangeTheTrip() {
        as("RAHUL", UserType.AGENT);
        assertThat(leadService.findAccessibleLead("L1")).isSameAs(lead);

        for (Executable write : tripWrites()) {
            assertThrows(AccessDeniedException.class, write);
        }
        verify(leadRepository, never()).save(any());
        verify(leadProposalRepository, never()).save(any());
        verify(leadProposalRepository, never()).delete(any());
    }

    @Test
    void anAgentAssignedToAServiceOnTheLeadCanChangeTheTrip() {
        as("VIKRAM", UserType.AGENT);
        leadService.addProposalItem("L1", tripLevelLine());
        leadService.setProposalLocked("L1", true);
        verify(leadProposalRepository).save(any());
        assertThat(lead.isProposalLocked()).isTrue();
    }

    @Test
    void theCreatorAndTheOwnerCanChangeTheTrip() {
        as("CREATOR", UserType.AGENT);
        leadService.setEscalated("L1", true, "Angry customer");
        as("O1", UserType.AGENCY_OWNER);
        leadService.setProposalLocked("L1", false);
        assertThat(lead.isProposalLocked()).isFalse();
    }

    /** Re-opening what the customer committed to is for the owner or the lead's creator only. */
    @Test
    void aServiceAgentCannotReopenAProposalTheCustomerConfirmed() {
        lead.setProposalLocked(true);
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                LeadProposal.builder().id("P2").leadId("L1").optionGroup("G").isSelected(true).selectedBy("CUSTOMER").build()));
        as("VIKRAM", UserType.AGENT);

        assertThrows(AccessDeniedException.class, () -> leadService.setProposalLocked("L1", false));
        assertThat(lead.isProposalLocked()).isTrue();

        as("CREATOR", UserType.AGENT);
        leadService.setProposalLocked("L1", false);
        assertThat(lead.isProposalLocked()).isFalse();
    }

    @Test
    void escalatingAndClearingAreWrittenToTheTimeline() {
        as("O1", UserType.AGENCY_OWNER);
        leadService.setEscalated("L1", true, "Angry customer");
        leadService.setEscalated("L1", false, null);
        verify(leadTimelineService).record("L1", com.voyra.crm.enums.LeadTimelineEventType.ESCALATED,
                "Escalated: Angry customer");
        verify(leadTimelineService).record("L1", com.voyra.crm.enums.LeadTimelineEventType.ESCALATION_CLEARED,
                "Escalation cleared");
    }

    @Test
    void aLostStatusChangeShowsItsReasonOnTheTimeline() {
        as("O1", UserType.AGENCY_OWNER);
        LeadStatusUpdateRequest status = new LeadStatusUpdateRequest();
        status.setStatus(LeadStatus.LOST);
        status.setLostReason("Too expensive");
        leadService.updateStatus("L1", status);
        verify(leadTimelineService).recordTransition("L1", com.voyra.crm.enums.LeadTimelineEventType.STATUS_CHANGED,
                LeadStatus.NEW, LeadStatus.LOST, "Status changed from NEW to LOST: Too expensive");
    }
}
