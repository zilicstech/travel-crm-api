package com.voyra.crm.service;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.AesPasswordEncoder;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Removing an agent is blocked while they still have open leads assigned. */
@ExtendWith(MockitoExtension.class)
class AgentRemovalTest {

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private LeadNoteRepository leadNoteRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private VisaRepository visaRepository;
    @Mock
    private AesPasswordEncoder passwordEncoder;

    @InjectMocks
    private AgentService agentService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void removalBlockedWhileAgentHasActiveLeads() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").name("Liam").build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        when(leadRepository.existsByAssignedToAndStatusNotIn(eq("A1"), any())).thenReturn(true);

        assertThatThrownBy(() -> agentService.removeAgent("A1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removalSucceedsWhenNoActiveLeadsRemain() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").name("Liam").build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        when(leadRepository.existsByAssignedToAndStatusNotIn(eq("A1"), any())).thenReturn(false);

        assertThatCode(() -> agentService.removeAgent("A1")).doesNotThrowAnyException();
    }
}
