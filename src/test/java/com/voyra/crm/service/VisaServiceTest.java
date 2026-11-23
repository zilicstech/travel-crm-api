package com.voyra.crm.service;

import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.VisaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisaServiceTest {

    @Mock
    private VisaRepository visaRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private VisaService visaService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsAgent(String id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "agent", UserType.AGENT, "T1"), null, List.of()));
    }

    @Test
    void anAgentWhoDoesNotHandleVisasCannotOpenAVisaCase() {
        signInAsAgent("A1");
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Rahul")
                .manageableServices(List.of(ServiceType.FLIGHT, ServiceType.HOTEL)).build()));
        VisaCreateRequest request = new VisaCreateRequest();
        request.setClientId("C1");
        request.setCountry("France");
        request.setVisaType("Tourist");

        assertThatThrownBy(() -> visaService.createVisa(request)).isInstanceOf(AccessDeniedException.class);
        verify(visaRepository, never()).save(any());
    }

    private Visa caseFor(String agentId) {
        Visa visa = Visa.builder().id("V1").agentId(agentId).clientName("C").country("France")
                .status(VisaStatus.DOCUMENTS_PENDING).build();
        when(visaRepository.findById("V1")).thenReturn(Optional.of(visa));
        return visa;
    }

    @Test
    void approvedAndRejectedTogetherIsRefused() {
        signInAsAgent("A1");
        caseFor("A1");
        VisaChecklistUpdateRequest request = new VisaChecklistUpdateRequest();
        request.setApproved(true);
        request.setRejected(true);

        assertThatThrownBy(() -> visaService.updateChecklist("V1", request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both approved and rejected");
        verify(visaRepository, never()).save(any());
    }

    @Test
    void anExpiryBeforeTheAppointmentIsRefused() {
        signInAsAgent("A1");
        caseFor("A1");
        VisaChecklistUpdateRequest request = new VisaChecklistUpdateRequest();
        request.setAppointmentDate(LocalDate.of(2026, 12, 1));
        request.setExpiryDate(LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> visaService.updateChecklist("V1", request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiry date");
    }

    @Test
    void unTickingApprovedClearsIt() {
        signInAsAgent("A1");
        Visa visa = caseFor("A1");
        visa.setApproved(true);
        VisaChecklistUpdateRequest request = new VisaChecklistUpdateRequest();
        request.setApproved(false);
        request.setRejected(false);

        visaService.updateChecklist("V1", request);

        assertThat(visa.getApproved()).isFalse();
        assertThat(visa.getStatus()).isEqualTo(VisaStatus.DOCUMENTS_PENDING);
    }
}
