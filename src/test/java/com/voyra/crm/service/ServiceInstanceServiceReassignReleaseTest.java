package com.voyra.crm.service;

import com.voyra.crm.dto.ServiceAssignRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner moves a service to another agent who handles its type and is active; the assigned
 * agent can hand it back to unclaimed until it is Confirmed / Booked / Cancelled. Both are on the timeline.
 */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceServiceReassignReleaseTest {

    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuditService auditService;
    @InjectMocks private ServiceInstanceService serviceInstanceService;

    private LeadService flight;

    @BeforeEach
    void setUp() {
        flight = LeadService.builder().id("S1").leadId("L1").type(ServiceType.FLIGHT).label("Flight")
                .status(ServiceStatus.IN_PROGRESS).assignedAgentId("VIKRAM").assignedAgentName("Vikram Rao").build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("O1").status(LeadStatus.NEW).build()));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flight));
        lenient().when(agentRepository.findByIdAndTenantId("RAHUL", "T1")).thenReturn(Optional.of(
                Agent.builder().id("RAHUL").name("Rahul Mehta").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        lenient().when(agentRepository.findByIdAndTenantId("GONE", "T1")).thenReturn(Optional.of(
                Agent.builder().id("GONE").name("Gone Agent").isActive(false).manageableServices(List.of(ServiceType.FLIGHT)).build()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private ServiceAssignRequest to(String agentId) {
        ServiceAssignRequest r = new ServiceAssignRequest();
        r.setAgentId(agentId);
        return r;
    }

    @Test
    void theOwnerReassignsToAnotherAgentWhoHandlesTheType() {
        as("O1", UserType.AGENCY_OWNER);
        serviceInstanceService.assignService("L1", "S1", to("RAHUL"));

        assertThat(flight.getAssignedAgentId()).isEqualTo("RAHUL");
        assertThat(flight.getAssignedAgentName()).isEqualTo("Rahul Mehta");
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_REASSIGNED,
                "Flight reassigned from Vikram Rao to Rahul Mehta");
    }

    @Test
    void anInactiveAgentCannotBeGivenTheService() {
        as("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> serviceInstanceService.assignService("L1", "S1", to("GONE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(flight.getAssignedAgentId()).isEqualTo("VIKRAM");
    }

    @Test
    void theAssignedAgentReleasesItBackToUnclaimed() {
        as("VIKRAM", UserType.AGENT);
        serviceInstanceService.releaseService("L1", "S1");

        assertThat(flight.getAssignedAgentId()).isNull();
        assertThat(flight.getAssignedAgentName()).isNull();
        assertThat(flight.getStatus()).isEqualTo(ServiceStatus.NOT_STARTED);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.SERVICE_RELEASED,
                "Vikram Rao released Flight");
    }

    @Test
    void onlyTheAssignedAgentCanRelease() {
        as("RAHUL", UserType.AGENT);
        assertThatThrownBy(() -> serviceInstanceService.releaseService("L1", "S1"))
                .isInstanceOf(AccessDeniedException.class);
        as("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> serviceInstanceService.releaseService("L1", "S1"))
                .isInstanceOf(AccessDeniedException.class);
        verify(leadServiceRepository, never()).save(any());
    }

    @Test
    void aBookedServiceCannotBeReleased() {
        as("VIKRAM", UserType.AGENT);
        flight.setStatus(ServiceStatus.BOOKED);
        assertThatThrownBy(() -> serviceInstanceService.releaseService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class);
        verify(leadTimelineService, never()).record(eq("L1"), eq("S1"), eq(LeadTimelineEventType.SERVICE_RELEASED), any());
    }
}
