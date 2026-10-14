package com.voyra.crm.service;

import com.voyra.crm.dto.PasswordResetRequest;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Owner resets an agent's/accountant's password: must confirm it, stays within own agency. */
@ExtendWith(MockitoExtension.class)
class AgentPasswordResetTest {

    @Mock private AgentRepository agentRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadNoteRepository leadNoteRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private VisaRepository visaRepository;
    @Mock private AesPasswordEncoder passwordEncoder;

    @InjectMocks private AgentService agentService;

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

    private static PasswordResetRequest request(String pw, String confirm) {
        PasswordResetRequest r = new PasswordResetRequest();
        r.setNewPassword(pw);
        r.setConfirmPassword(confirm);
        return r;
    }

    @Test
    void matchingPasswordIsEncodedAndSaved() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").userRole(UserType.ACCOUNTANT).password("old").build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        when(passwordEncoder.encode("NewPass123")).thenReturn("enc");

        agentService.resetPassword("A1", request("NewPass123", "NewPass123"));

        assertThat(agent.getPassword()).isEqualTo("enc");
        verify(agentRepository).save(agent);
    }

    @Test
    void mismatchedConfirmationIsRejectedAndNothingChanges() {
        Agent agent = Agent.builder().id("A1").tenantId("T1").password("old").build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> agentService.resetPassword("A1", request("NewPass123", "Different1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");

        assertThat(agent.getPassword()).isEqualTo("old");
        verify(agentRepository, never()).save(agent);
    }

    @Test
    void otherAgencyUserCannotBeReset() {
        Agent agent = Agent.builder().id("A9").tenantId("OTHER").password("old").build();
        when(agentRepository.findById("A9")).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> agentService.resetPassword("A9", request("NewPass123", "NewPass123")))
                .isInstanceOf(AccessDeniedException.class);
        verify(agentRepository, never()).save(agent);
    }
}
