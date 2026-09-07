package com.voyra.crm.service;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.when;

/**
 * An agent may only claim a service whose type they manage, and only while it is still open
 * (unassigned and not CONFIRMED/CANCELLED). {@link LeadService#getAssignedAgentId()} and
 * {@link LeadService#getStatus()} are the only two facts {@link ServiceInstanceService#acceptService}
 * consults - this pins both rejection paths so a regression there fails loudly rather than
 * silently letting an agent claim work outside their remit.
 */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceServiceAcceptTest {

    @Mock
    private LeadServiceRepository leadServiceRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private LeadProposalRepository leadProposalRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LeadTimelineService leadTimelineService;

    @InjectMocks
    private ServiceInstanceService serviceInstanceService;

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Lead lead() {
        return Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEW).build();
    }

    @Test
    void agentCannotAcceptAServiceOfATypeTheyDoNotManage() {
        authenticateAs("A2", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService service = LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.VISA).status(ServiceStatus.NOT_STARTED).build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        Agent agent = Agent.builder().id("A2").manageableServices(List.of(ServiceType.FLIGHT)).build();
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> serviceInstanceService.acceptService("L1", "S1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentCannotAcceptAnAlreadyAssignedService() {
        authenticateAs("A2", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService service = LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).status(ServiceStatus.IN_PROGRESS).assignedAgentId("A3").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        Agent agent = Agent.builder().id("A2").manageableServices(List.of(ServiceType.FLIGHT)).build();
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> serviceInstanceService.acceptService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void agentCannotAcceptAConfirmedService() {
        authenticateAs("A2", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService service = LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).status(ServiceStatus.CONFIRMED).build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        Agent agent = Agent.builder().id("A2").manageableServices(List.of(ServiceType.FLIGHT)).build();
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> serviceInstanceService.acceptService("L1", "S1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void agentClaimingAnOpenServiceOfAManagedTypeSucceeds() {
        authenticateAs("A2", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadService service = LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).status(ServiceStatus.NOT_STARTED).label("Flight").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        Agent agent = Agent.builder().id("A2").name("Priya").manageableServices(List.of(ServiceType.FLIGHT)).build();
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent));

        serviceInstanceService.acceptService("L1", "S1");

        assertThat(service.getAssignedAgentId()).isEqualTo("A2");
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.IN_PROGRESS);
    }
}
