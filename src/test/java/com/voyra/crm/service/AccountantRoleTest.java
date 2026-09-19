package com.voyra.crm.service;

import com.voyra.crm.dto.AgentCreateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Accountant role reuses the agent table (UserType.ACCOUNTANT discriminator) rather than
 * a new login table - see travel-crm-backend architecture notes. These tests pin the two
 * behavioural differences from a regular AGENT: manageableServices is not required, and
 * listAgents() must never surface an accountant to an assignee picker.
 */
@ExtendWith(MockitoExtension.class)
class AccountantRoleTest {

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private LeadNoteRepository leadNoteRepository;
    @Mock
    private BookingRepository bookingRepository;
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
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creatingAnAccountantDoesNotRequireManageableServices() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("Neha Kapoor");
        request.setEmail("neha@example.com");
        request.setPassword("SecurePass123");
        request.setUserRole(UserType.ACCOUNTANT);

        when(agentRepository.existsByEmailIgnoreCase(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded");

        agentService.createAgent(request);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        org.mockito.Mockito.verify(agentRepository).save(captor.capture());
        assertThat(captor.getValue().getUserRole()).isEqualTo(UserType.ACCOUNTANT);
        assertThat(captor.getValue().getManageableServices()).isEmpty();
    }

    @Test
    void creatingAnAgentWithoutManageableServicesIsRejected() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("Liam Smith");
        request.setEmail("liam@example.com");
        request.setPassword("SecurePass123");
        request.setUserRole(UserType.AGENT);
        request.setManageableServices(List.of());

        when(agentRepository.existsByEmailIgnoreCase(request.getEmail())).thenReturn(false);

        assertThatThrownBy(() -> agentService.createAgent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manageable service");
    }

    @Test
    void creatingAStaffMemberWithAnInvalidRoleIsRejected() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("X");
        request.setEmail("x@example.com");
        request.setPassword("SecurePass123");
        request.setUserRole(UserType.AGENCY_OWNER);

        when(agentRepository.existsByEmailIgnoreCase(request.getEmail())).thenReturn(false);

        assertThatThrownBy(() -> agentService.createAgent(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listAgentsDefaultsToAgentRoleOnly() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of());

        agentService.listAgents();

        org.mockito.Mockito.verify(agentRepository).findByTenantIdAndUserRole("T1", UserType.AGENT);
        org.mockito.Mockito.verify(agentRepository, org.mockito.Mockito.never())
                .findByTenantIdAndUserRole(any(), org.mockito.ArgumentMatchers.eq(UserType.ACCOUNTANT));
    }

    @Test
    void listAgentsCanBeFilteredToAccountants() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.ACCOUNTANT)).thenReturn(List.of());

        agentService.listAgents(UserType.ACCOUNTANT);

        org.mockito.Mockito.verify(agentRepository).findByTenantIdAndUserRole("T1", UserType.ACCOUNTANT);
    }
}
