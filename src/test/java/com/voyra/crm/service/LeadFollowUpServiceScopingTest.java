package com.voyra.crm.service;

import com.voyra.crm.dto.FollowUpCreateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * An agent may write or list follow-ups on a lead they created, one assigned to them, or - the
 * gap this test pins - a lead where they hold or manage a service, the same reach
 * {@link com.voyra.crm.util.LeadAccessChecker#hasServiceAccess} grants everywhere else. Before
 * this, the javadoc on findAccessibleLead promised the service-type branch but the code lacked
 * it, so a visa agent could open a lead via its service but get a 403 adding a follow-up to it.
 */
@ExtendWith(MockitoExtension.class)
class LeadFollowUpServiceScopingTest {

    @Mock
    private LeadFollowUpRepository leadFollowUpRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private LeadServiceRepository leadServiceRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LeadTimelineService leadTimelineService;

    @InjectMocks
    private LeadFollowUpService leadFollowUpService;

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentWithNoRouteToTheLeadIsDenied() {
        authenticateAs("A2", UserType.AGENT);
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEW).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of());
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").manageableServices(List.of(ServiceType.FLIGHT)).build()));

        assertThatThrownBy(() -> leadFollowUpService.listForLead("L1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentWhoManagesAServiceTypeOnTheLeadCanAddAFollowUp() {
        authenticateAs("A2", UserType.AGENT);
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEW)
                .clientName("Client").destination("Paris").build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService visaService = LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).build();
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(visaService));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").name("Priya").manageableServices(List.of(ServiceType.VISA)).build()));
        when(agentRepository.findById("A3")).thenReturn(Optional.of(
                Agent.builder().id("A3").name("Someone Else").build()));

        FollowUpCreateRequest req = new FollowUpCreateRequest();
        req.setDueDate(LocalDate.now().plusDays(3));
        req.setNote("Chase the embassy");
        req.setAssignedAgentId("A3");
        req.setServiceType(ServiceType.VISA);

        // Should not throw - the agent reaches this lead via their managed VISA service.
        leadFollowUpService.addFollowUp("L1", req);
    }

    /**
     * Regression for a real exploit found in this session's QA sweep: addFollowUp used to
     * accept {@code requestedAssigneeId.equals(principal.userId())} as an access grant on its
     * own, with no service-access check at all - so any agent could plant a follow-up on any
     * lead in the tenant, zero relationship to it required, just by naming themselves as the
     * assignee. Confirmed live: an agent managing HOTEL/TRANSFER only successfully wrote a
     * follow-up onto a lead whose only services were FLIGHT/VISA, by setting
     * assignedAgentId to their own id. Fixed by routing addFollowUp through
     * findAccessibleLeadForCreate, which drops the self-assign bypass entirely - only
     * completeFollowUp/deleteFollowUp keep it, and only because their assigneeId is read back
     * from an already-persisted row, never chosen fresh by the caller.
     */
    @Test
    void agentCannotCreateAFollowUpOnAnUnrelatedLeadByAssigningItToThemselves() {
        authenticateAs("A2", UserType.AGENT);
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEW)
                .clientName("Client").destination("Paris").build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        LeadService flightService = LeadService.builder().id("S1").leadId("L1").type(ServiceType.FLIGHT).build();
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(flightService));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").name("Emma").manageableServices(List.of(ServiceType.HOTEL)).build()));

        FollowUpCreateRequest req = new FollowUpCreateRequest();
        req.setDueDate(LocalDate.now().plusDays(3));
        req.setNote("should be rejected");
        req.setAssignedAgentId("A2");

        assertThatThrownBy(() -> leadFollowUpService.addFollowUp("L1", req))
                .isInstanceOf(AccessDeniedException.class);
    }
}
