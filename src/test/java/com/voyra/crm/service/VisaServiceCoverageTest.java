package com.voyra.crm.service;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaDashboardSummaryResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.AuditEntityType;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisaServiceCoverageTest {

    @Mock VisaRepository visaRepository;
    @Mock ClientRepository clientRepository;
    @Mock AgentRepository agentRepository;
    @Mock AuditService auditService;
    @InjectMocks VisaService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    private static Agent visaAgent(String id) {
        return Agent.builder().id(id).name("Priya").manageableServices(List.of(ServiceType.VISA)).build();
    }

    private static VisaCreateRequest request(String agentId) {
        VisaCreateRequest r = new VisaCreateRequest();
        r.setClientId("C1");
        r.setAgentId(agentId);
        r.setLeadId("L1");
        r.setCountry("France");
        r.setVisaType("Tourist");
        r.setPassportNumber("X0000000");
        return r;
    }

    @Test
    void aVisaAgentOpensACaseForThemselvesDatedToday() {
        signInAs("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(visaAgent("A1")));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        when(visaRepository.existsById(anyString())).thenReturn(false);

        VisaResponse res = service.createVisa(request("IGNORED"));

        ArgumentCaptor<Visa> saved = ArgumentCaptor.forClass(Visa.class);
        verify(visaRepository).save(saved.capture());
        Visa v = saved.getValue();
        assertThat(v.getAgentId()).isEqualTo("A1");
        assertThat(v.getAgentName()).isEqualTo("Priya");
        assertThat(v.getCreatedBy()).isEqualTo("A1");
        assertThat(v.getClientName()).isEqualTo("Asha");
        assertThat(v.getApplicationDate()).isEqualTo(LocalDate.now());
        assertThat(v.getStatus()).isEqualTo(VisaStatus.DOCUMENTS_PENDING);
        assertThat(res.getCountry()).isEqualTo("France");
        assertThat(res.getPassportNumber()).isEqualTo("X0000000");
        assertThat(res.getLeadId()).isEqualTo("L1");
        verify(auditService).recordCreate(AuditEntityType.VISA, v.getId(), "Asha / France");
    }

    @Test
    void anOwnerOpensACaseForAVisaAgentWithTheGivenDate() {
        signInAs("O1", UserType.AGENCY_OWNER);
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(visaAgent("A1")));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        when(visaRepository.existsById(anyString())).thenReturn(false);
        VisaCreateRequest req = request("A1");
        req.setApplicationDate(LocalDate.of(2026, 9, 1));

        VisaResponse res = service.createVisa(req);

        assertThat(res.getAgentId()).isEqualTo("A1");
        assertThat(res.getApplicationDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void createRefusesAnUnknownClient() {
        signInAs("O1", UserType.AGENCY_OWNER);
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(visaAgent("A1")));
        when(clientRepository.findById("C1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVisa(request("A1"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Client not found: C1");
        verifyNoInteractions(visaRepository);
    }

    @Test
    void anAgentMissingFromTheDatabaseIsAnError() {
        signInAs("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVisa(request(null))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent not found: A1");
    }

    @Test
    void anAgentWithNoServicesCannotOpenACase() {
        signInAs("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Priya").manageableServices(null).build()));

        assertThatThrownBy(() -> service.createVisa(request(null))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(visaRepository);
    }

    @Test
    void anOwnerMustNameTheAgent() {
        signInAs("O1", UserType.AGENCY_OWNER);

        assertThatThrownBy(() -> service.createVisa(request(null))).hasMessage("agentId is required when an Owner creates a visa case");
        assertThatThrownBy(() -> service.createVisa(request("  "))).hasMessage("agentId is required when an Owner creates a visa case");
        verifyNoInteractions(agentRepository, visaRepository);
    }

    @Test
    void anOwnerCannotPickAnAgentOfAnotherAgencyOrOneWithNoServices() {
        signInAs("O1", UserType.AGENCY_OWNER);
        when(agentRepository.findByIdAndTenantId("AX", "T1")).thenReturn(Optional.empty());
        when(agentRepository.findByIdAndTenantId("A2", "T1")).thenReturn(Optional.of(Agent.builder().id("A2").name("Ravi").manageableServices(null).build()));

        assertThatThrownBy(() -> service.createVisa(request("AX"))).hasMessage("Agent not found in this agency: AX");
        assertThatThrownBy(() -> service.createVisa(request("A2"))).hasMessage("Ravi doesn't handle visas - pick an agent who does");
        verifyNoInteractions(visaRepository);
    }

    @Test
    void anAgentListsOnlyTheirCasesAndAnOwnerListsAll() {
        Visa mine = Visa.builder().id("V1").agentId("A1").status(VisaStatus.SUBMITTED).build();
        Visa other = Visa.builder().id("V2").agentId("A2").status(VisaStatus.APPROVED).build();
        when(visaRepository.findByAgentId("A1")).thenReturn(List.of(mine));
        when(visaRepository.findAll()).thenReturn(List.of(mine, other));

        signInAs("A1", UserType.AGENT);
        assertThat(service.listVisas()).extracting(VisaResponse::getId).containsExactly("V1");
        signInAs("O1", UserType.AGENCY_OWNER);
        assertThat(service.listVisas()).extracting(VisaResponse::getId).containsExactly("V1", "V2");
    }

    @Test
    void pagedListingIsScopedTheSameWay() {
        PageRequest page = PageRequest.of(0, 10);
        Visa mine = Visa.builder().id("V1").agentId("A1").build();
        when(visaRepository.findByAgentId("A1", page)).thenReturn(new PageImpl<>(List.of(mine), page, 1));
        when(visaRepository.findAll(page)).thenReturn(new PageImpl<>(List.of(mine, mine), page, 2));

        signInAs("A1", UserType.AGENT);
        PagedResponse<VisaResponse> agentPage = service.listVisas(page);
        signInAs("O1", UserType.AGENCY_OWNER);
        PagedResponse<VisaResponse> ownerPage = service.listVisas(page);

        assertThat(agentPage.getTotalElements()).isEqualTo(1);
        assertThat(ownerPage.getTotalElements()).isEqualTo(2);
    }

    @Test
    void anAgentCannotOpenAnotherAgentsCaseButTheOwnerCan() {
        Visa v = Visa.builder().id("V1").agentId("A2").build();
        when(visaRepository.findById("V1")).thenReturn(Optional.of(v));

        signInAs("A1", UserType.AGENT);
        assertThatThrownBy(() -> service.getVisa("V1")).isInstanceOf(AccessDeniedException.class)
                .hasMessage("This visa case is not assigned to you");
        signInAs("A2", UserType.AGENT);
        assertThat(service.getVisa("V1").getId()).isEqualTo("V1");
        signInAs("O1", UserType.AGENCY_OWNER);
        assertThat(service.getVisa("V1").getId()).isEqualTo("V1");
    }

    @Test
    void getRefusesAnUnknownCase() {
        when(visaRepository.findById("V9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVisa("V9")).hasMessage("Visa case not found: V9");
    }

    @Test
    void checklistUpdateSetsEveryFieldAndApprovalClearsRejection() {
        signInAs("O1", UserType.AGENCY_OWNER);
        Visa v = Visa.builder().id("V1").agentId("A1").clientName("Asha").country("France").rejected(true).build();
        when(visaRepository.findById("V1")).thenReturn(Optional.of(v));
        VisaChecklistUpdateRequest r = new VisaChecklistUpdateRequest();
        r.setPassportCollected(true);
        r.setPhotosCollected(true);
        r.setFormsFilled(true);
        r.setAppointmentDate(LocalDate.of(2026, 11, 1));
        r.setBiometricsDone(true);
        r.setSubmittedToEmbassy(true);
        r.setPassportReturned(false);
        r.setVisaValidity("90 days");
        r.setExpiryDate(LocalDate.of(2027, 2, 1));
        r.setApproved(true);

        VisaResponse res = service.updateChecklist("V1", r);

        assertThat(v.getPassportCollected()).isTrue();
        assertThat(v.getPhotosCollected()).isTrue();
        assertThat(v.getFormsFilled()).isTrue();
        assertThat(v.getBiometricsDone()).isTrue();
        assertThat(v.getSubmittedToEmbassy()).isTrue();
        assertThat(v.getPassportReturned()).isFalse();
        assertThat(v.getVisaValidity()).isEqualTo("90 days");
        assertThat(v.getApproved()).isTrue();
        assertThat(v.getRejected()).isFalse();
        assertThat(v.getStatus()).isEqualTo(VisaStatus.APPROVED);
        assertThat(v.getUpdatedBy()).isEqualTo("O1");
        assertThat(res.getStatus()).isEqualTo(VisaStatus.APPROVED);
        verify(visaRepository).save(v);
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA), eq("V1"), eq("Asha / France"), anyList());
    }

    @Test
    void rejectionClearsApproval() {
        signInAs("O1", UserType.AGENCY_OWNER);
        Visa v = Visa.builder().id("V1").agentId("A1").approved(true).expiryDate(LocalDate.of(2027, 1, 1)).build();
        when(visaRepository.findById("V1")).thenReturn(Optional.of(v));
        VisaChecklistUpdateRequest r = new VisaChecklistUpdateRequest();
        r.setRejected(true);

        service.updateChecklist("V1", r);

        assertThat(v.getApproved()).isFalse();
        assertThat(v.getRejected()).isTrue();
        assertThat(v.getStatus()).isEqualTo(VisaStatus.REJECTED);
    }

    @Test
    void approvedWithoutRejectedFieldIsAccepted() {
        signInAs("O1", UserType.AGENCY_OWNER);
        Visa v = Visa.builder().id("V1").agentId("A1").build();
        when(visaRepository.findById("V1")).thenReturn(Optional.of(v));
        VisaChecklistUpdateRequest r = new VisaChecklistUpdateRequest();
        r.setApproved(true);
        r.setRejected(false);

        service.updateChecklist("V1", r);

        assertThat(v.getStatus()).isEqualTo(VisaStatus.APPROVED);
    }

    @Test
    void dashboardCountsEachStageForTheCallersScope() {
        List<Visa> all = List.of(
                Visa.builder().status(VisaStatus.DOCUMENTS_PENDING).build(),
                Visa.builder().status(VisaStatus.APPOINTMENT_SCHEDULED).build(),
                Visa.builder().status(VisaStatus.SUBMITTED).build(),
                Visa.builder().status(VisaStatus.APPROVED).passportReturned(true).build(),
                Visa.builder().status(VisaStatus.APPROVED).passportReturned(false).build(),
                Visa.builder().status(VisaStatus.REJECTED).passportReturned(true).build());
        when(visaRepository.findAll()).thenReturn(all);
        when(visaRepository.findByAgentId("A1")).thenReturn(List.of(all.get(0)));

        signInAs("O1", UserType.AGENCY_OWNER);
        VisaDashboardSummaryResponse s = service.getDashboardSummary();
        signInAs("A1", UserType.AGENT);
        VisaDashboardSummaryResponse mine = service.getDashboardSummary();

        assertThat(s.getTotal()).isEqualTo(6);
        assertThat(s.getDocumentsPending()).isEqualTo(1);
        assertThat(s.getAppointmentScheduled()).isEqualTo(1);
        assertThat(s.getSubmitted()).isEqualTo(1);
        assertThat(s.getApproved()).isEqualTo(2);
        assertThat(s.getRejected()).isEqualTo(1);
        assertThat(s.getPassportReturned()).isEqualTo(2);
        assertThat(s.getPassportCollectionPending()).isEqualTo(1);
        assertThat(mine.getTotal()).isEqualTo(1);
    }
}
