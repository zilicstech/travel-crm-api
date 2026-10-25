package com.voyra.crm.service;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.TenantWriteRefusedException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The interceptors let PATCH /api/agents/{id}/status through for a SUSPENDED or READ_ONLY agency so an owner
 * can always cut a login off; reactivating through the same route must still be refused, here.
 */
@ExtendWith(MockitoExtension.class)
class AgentReactivationGuardTest {

    @Mock private AgentRepository agentRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadNoteRepository leadNoteRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private VisaRepository visaRepository;
    @Mock private EntitlementService entitlementService;
    @InjectMocks private AgentService agentService;

    private Agent agent;

    @BeforeEach
    void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, java.util.List.of()));
        agent = Agent.builder().id("A1").tenantId("T1").name("Liam").isActive(false).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AgentCache.remove("A1");
    }

    @Test
    void reactivationIsRefusedWhenTheAgencyCannotWrite() {
        doThrow(TenantWriteRefusedException.readOnly()).when(entitlementService).assertWritable("T1");

        assertThatThrownBy(() -> agentService.updateStatus("A1", true)).isInstanceOf(TenantWriteRefusedException.class);
        verify(agentRepository, never()).save(any());
        assertThat(agent.getIsActive()).isFalse();
    }

    @Test
    void deactivationNeverAsksForAWritableSubscription() {
        agent.setIsActive(true);
        agentService.updateStatus("A1", false);
        verify(entitlementService, never()).assertWritable(any());
        verify(agentRepository).save(agent);
        assertThat(agent.getIsActive()).isFalse();
    }
}
