package com.voyra.crm.service;

import com.voyra.crm.dto.FollowUpCreateRequest;
import com.voyra.crm.dto.FollowUpResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadFollowUpServiceCoverageTest {

    @Mock private LeadFollowUpRepository leadFollowUpRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @InjectMocks private LeadFollowUpService service;

    private static final LocalDate DUE = LocalDate.of(2026, 11, 5);

    @BeforeEach
    void owner() {
        as("O1", UserType.AGENCY_OWNER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "u", type, "T1"), null, List.of()));
    }

    private Lead lead(String createdBy) {
        Lead lead = Lead.builder().id("L1").createdBy(createdBy).clientName("Asha").destination("Goa").build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        return lead;
    }

    private static LeadFollowUp.LeadFollowUpBuilder followUp() {
        return LeadFollowUp.builder().id("F1").leadId("L1").note("Call about hotel").assignedAgentId("A2").status(FollowUpStatus.OPEN).dueDate(DUE);
    }

    private void rollup(long open, LocalDate earliest) {
        when(leadFollowUpRepository.countByLeadIdAndStatus("L1", FollowUpStatus.OPEN)).thenReturn(open);
        when(leadFollowUpRepository.findFirstByLeadIdAndStatusOrderByDueDateAsc("L1", FollowUpStatus.OPEN))
                .thenReturn(Optional.ofNullable(earliest == null ? null : followUp().dueDate(earliest).build()));
    }

    @Test
    void anOwnersTripLevelFollowUpIsAssignedAndRolledUpOnTheLead() {
        Lead lead = lead("A2");
        when(agentRepository.findById("A2")).thenReturn(Optional.of(Agent.builder().id("A2").name("Vikram").build()));
        rollup(2, DUE.minusDays(1));
        FollowUpCreateRequest r = new FollowUpCreateRequest();
        r.setAssignedAgentId("A2");
        r.setDueDate(DUE);
        r.setNote("Send visa list");

        FollowUpResponse response = service.addFollowUp("L1", r);

        assertThat(response.getAssignedAgentName()).isEqualTo("Vikram");
        assertThat(response.getClientName()).isEqualTo("Asha");
        assertThat(response.getLeadDestination()).isEqualTo("Goa");
        assertThat(response.getStatus()).isEqualTo(FollowUpStatus.OPEN);
        ArgumentCaptor<LeadFollowUp> saved = ArgumentCaptor.forClass(LeadFollowUp.class);
        verify(leadFollowUpRepository).save(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("O1");
        assertThat(lead.getOpenFollowUps()).isEqualTo(2);
        assertThat(lead.getFollowUpDate()).isEqualTo(DUE.minusDays(1));
        verify(leadRepository).save(lead);
        verify(leadTimelineService).record("L1", LeadTimelineEventType.FOLLOW_UP_SET, "Follow-up set for " + DUE);
    }

    @Test
    void anAgentSetsAFollowUpForATypeTheyHandleOnTheirOwnLead() {
        as("A1", UserType.AGENT);
        lead("A1");
        Agent me = Agent.builder().id("A1").name("Priya").manageableServices(List.of(ServiceType.HOTEL)).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(me));
        rollup(1, DUE);
        FollowUpCreateRequest r = new FollowUpCreateRequest();
        r.setAssignedAgentId("A1");
        r.setDueDate(DUE);
        r.setServiceType(ServiceType.HOTEL);

        assertThat(service.addFollowUp("L1", r).getServiceType()).isEqualTo(ServiceType.HOTEL);
        verify(leadTimelineService).record("L1", LeadTimelineEventType.FOLLOW_UP_SET, "Follow-up set for " + DUE + " (HOTEL)");
    }

    @Test
    void anAgentsTripLevelFollowUpNeedsNoServiceType() {
        as("A1", UserType.AGENT);
        lead("A1");
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Priya").manageableServices(List.of()).build()));
        rollup(1, DUE);
        FollowUpCreateRequest r = new FollowUpCreateRequest();
        r.setAssignedAgentId("A1");
        r.setDueDate(DUE);

        assertThat(service.addFollowUp("L1", r).getAssignedAgentName()).isEqualTo("Priya");
        verify(agentRepository, org.mockito.Mockito.times(1)).findById("A1");
    }

    @Test
    void anAgentWithoutARecordOrWithNoServicesCannotSetATypedFollowUp() {
        as("A1", UserType.AGENT);
        lead("A1");
        when(agentRepository.findById("A1")).thenReturn(Optional.empty(),
                Optional.of(Agent.builder().id("A1").manageableServices(null).build()));
        FollowUpCreateRequest r = new FollowUpCreateRequest();
        r.setServiceType(ServiceType.VISA);

        assertThatThrownBy(() -> service.addFollowUp("L1", r)).isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
        assertThatThrownBy(() -> service.addFollowUp("L1", r)).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only agents who handle VISA can set its follow-ups");
        verify(leadFollowUpRepository, never()).save(any());
    }

    @Test
    void theAssigneeMustExistAndTheLeadToo() {
        assertThatThrownBy(() -> service.addFollowUp("L9", new FollowUpCreateRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: L9");
        lead("A2");
        FollowUpCreateRequest r = new FollowUpCreateRequest();
        r.setAssignedAgentId("A9");
        assertThatThrownBy(() -> service.addFollowUp("L1", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found: A9");
    }

    @Test
    void completingAFollowUpClosesItAndRecomputesTheLead() {
        Lead lead = lead("A2");
        LeadFollowUp f = followUp().build();
        when(leadFollowUpRepository.findByIdAndLeadId("F1", "L1")).thenReturn(Optional.of(f));
        rollup(0, null);

        FollowUpResponse r = service.completeFollowUp("L1", "F1");

        assertThat(r.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(r.getCompletedAt()).isNotNull();
        assertThat(lead.getOpenFollowUps()).isZero();
        assertThat(lead.getFollowUpDate()).isNull();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.FOLLOW_UP_SET, "Follow-up completed: Call about hotel");
    }

    @Test
    void theAssignedAgentMayCloseTheirFollowUpEvenWithoutOtherAccess() {
        as("A2", UserType.AGENT);
        lead("A3");
        LeadFollowUp f = followUp().build();
        when(leadFollowUpRepository.findByIdAndLeadId("F1", "L1")).thenReturn(Optional.of(f));
        rollup(0, null);

        service.deleteFollowUp("L1", "F1");

        verify(leadFollowUpRepository).delete(f);
        verify(leadTimelineService).record("L1", LeadTimelineEventType.FOLLOW_UP_SET, "Follow-up removed: Call about hotel");
        verifyNoInteractions(leadServiceRepository);
    }

    @Test
    void theLeadsCreatorOrAServiceHolderMayCloseAFollowUp() {
        LeadFollowUp f = followUp().assignedAgentId(null).build();
        when(leadFollowUpRepository.findByIdAndLeadId("F1", "L1")).thenReturn(Optional.of(f));
        lead("A1");
        rollup(0, null);
        as("A1", UserType.AGENT);
        assertThat(service.completeFollowUp("L1", "F1").getStatus()).isEqualTo(FollowUpStatus.DONE);

        as("A4", UserType.AGENT);
        when(agentRepository.findById("A4")).thenReturn(Optional.of(Agent.builder().id("A4").manageableServices(List.of()).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1"))
                .thenReturn(List.of(LeadService.builder().assignedAgentId("A4").type(ServiceType.FLIGHT).build()));
        service.deleteFollowUp("L1", "F1");
        verify(leadFollowUpRepository).delete(f);
    }

    @Test
    void anAgentWithNoRouteCannotCloseSomeoneElsesFollowUp() {
        as("A5", UserType.AGENT);
        lead("A1");
        when(leadFollowUpRepository.findByIdAndLeadId("F1", "L1")).thenReturn(Optional.of(followUp().build()));

        assertThatThrownBy(() -> service.completeFollowUp("L1", "F1"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This lead is not accessible to you");
        verify(leadFollowUpRepository, never()).save(any());
    }

    @Test
    void closingNeedsTheFollowUpAndItsLead() {
        assertThatThrownBy(() -> service.deleteFollowUp("L1", "F9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Follow-up not found: F9");
        when(leadFollowUpRepository.findByIdAndLeadId("F1", "L1")).thenReturn(Optional.of(followUp().build()));
        assertThatThrownBy(() -> service.completeFollowUp("L1", "F1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: L1");
    }

    @Test
    void aLeadsFollowUpsAreListedByDueDate() {
        lead("A2");
        when(leadFollowUpRepository.findByLeadIdOrderByDueDateAsc("L1")).thenReturn(List.of(followUp().build()));
        assertThat(service.listForLead("L1")).extracting(FollowUpResponse::getId).containsExactly("F1");
    }

    @Test
    void anAgentWithAServiceOnTheLeadMayListItsFollowUps() {
        as("A4", UserType.AGENT);
        lead("A1");
        when(agentRepository.findById("A4")).thenReturn(Optional.of(Agent.builder().id("A4").manageableServices(List.of(ServiceType.VISA)).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(LeadService.builder().type(ServiceType.VISA).build()));
        when(leadFollowUpRepository.findByLeadIdOrderByDueDateAsc("L1")).thenReturn(List.of());
        assertThat(service.listForLead("L1")).isEmpty();
    }

    @Test
    void theBoardPicksItsQueryFromTheFilters() {
        LeadFollowUp f = followUp().build();
        when(leadFollowUpRepository.findAll()).thenReturn(List.of(f));
        when(leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc("A2", FollowUpStatus.OPEN, DUE))
                .thenReturn(List.of(f));
        when(leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc("A2", FollowUpStatus.DONE, DUE))
                .thenReturn(List.of());
        when(leadFollowUpRepository.findByAssignedAgentIdAndStatusOrderByDueDateAsc("A2", FollowUpStatus.DONE)).thenReturn(List.of());
        when(leadFollowUpRepository.findByAssignedAgentIdOrderByDueDateAsc("A2")).thenReturn(List.of(f, f));

        assertThat(service.board(null, FollowUpStatus.OPEN, DUE)).hasSize(1);
        assertThat(service.board("A2", null, DUE)).hasSize(1);
        assertThat(service.board("A2", FollowUpStatus.DONE, DUE)).isEmpty();
        assertThat(service.board("A2", FollowUpStatus.DONE, null)).isEmpty();
        assertThat(service.board("A2", null, null)).hasSize(2);
    }

    @Test
    void anAgentsBoardIsAlwaysTheirOwn() {
        as("A1", UserType.AGENT);
        when(leadFollowUpRepository.findByAssignedAgentIdOrderByDueDateAsc("A1")).thenReturn(List.of());

        assertThat(service.board("A2", null, null)).isEmpty();
        verify(leadFollowUpRepository, never()).findByAssignedAgentIdOrderByDueDateAsc("A2");
    }
}
