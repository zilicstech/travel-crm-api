package com.voyra.crm.service;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.dto.AgentCreateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §8: seats = active, non-deleted staff logins; checked on create and reactivate, never cutting anyone off. */
@ExtendWith(MockitoExtension.class)
class AgentSeatLimitTest {

    @Mock private AgentRepository agentRepository;
    @Mock private com.voyra.crm.repository.LeadRepository leadRepository;
    @Mock private com.voyra.crm.repository.LeadNoteRepository leadNoteRepository;
    @Mock private com.voyra.crm.repository.BookingRepository bookingRepository;
    @Mock private com.voyra.crm.repository.VisaRepository visaRepository;
    @Mock private AesPasswordEncoder passwordEncoder;
    @Mock private LoginEmailService loginEmailService;
    @Mock private EntitlementService entitlementService;
    @Mock private com.voyra.crm.repository.SubscriptionRepository subscriptionRepository;
    @InjectMocks private AgentService agentService;

    @BeforeEach
    void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AgentCache.remove("A1");
    }

    private void seats(int total, long used) {
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, null, Set.of(), total, false, null));
        when(agentRepository.countByTenantIdAndIsActiveTrue("T1")).thenReturn(used);
    }

    private AgentCreateRequest request() {
        AgentCreateRequest r = new AgentCreateRequest();
        r.setName("Liam");
        r.setEmail("liam@x.com");
        r.setPassword("pw");
        r.setManageableServices(List.of(ServiceType.FLIGHT));
        return r;
    }

    @Test
    void createIsRefusedWhenAllSeatsAreInUse() {
        seats(5, 5);
        assertThatThrownBy(() -> agentService.createAgent(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("All 5 seats are in use — add seats in Subscription");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void theTenantSubscriptionRowIsLockedBeforeSeatsAreCounted() {
        seats(5, 4);
        agentService.createAgent(request());
        InOrder order = inOrder(subscriptionRepository, agentRepository);
        order.verify(subscriptionRepository).findByTenantIdForUpdate("T1");
        order.verify(agentRepository).countByTenantIdAndIsActiveTrue("T1");
    }

    @Test
    void zeroSeatsSaysTheAddOnIsNeeded() {
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.STARTER, null, Set.of(), 0, false, null));
        assertThatThrownBy(() -> agentService.createAgent(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Staff logins need the Staff seats add-on — add it in Subscription");
    }

    @Test
    void reactivatingAnAlreadyActiveAgentSkipsTheSeatCheck() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").isActive(true).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        agentService.updateStatus("A1", true);
        verify(entitlementService, never()).resolve(any());
        verify(subscriptionRepository, never()).findByTenantIdForUpdate(any());
    }

    @Test
    void createSucceedsWithASeatFree() {
        seats(5, 4);
        agentService.createAgent(request());
        verify(agentRepository).save(any(Agent.class));
    }

    @Test
    void reactivationIsRefusedAtTheSeatLimitAfterTheWritableCheck() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").isActive(false).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        seats(2, 2);

        assertThatThrownBy(() -> agentService.updateStatus("A1", true))
                .isInstanceOf(IllegalStateException.class).hasMessage("All 2 seats are in use — add seats in Subscription");
        InOrder order = inOrder(entitlementService);
        order.verify(entitlementService).assertWritable("T1");
        order.verify(entitlementService).resolve("T1");
        assertThat(agent.getIsActive()).isFalse();
        verify(agentRepository, never()).save(any());
    }

    @Test
    void deactivationNeverChecksSeats() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").isActive(true).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        agentService.updateStatus("A1", false);
        verify(entitlementService, never()).resolve(any());
        assertThat(agent.getIsActive()).isFalse();
    }
}
