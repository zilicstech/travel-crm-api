package com.voyra.crm.service;

import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.ClientCreditResponse;
import com.voyra.crm.dto.ClientDetailResponse;
import com.voyra.crm.dto.ClientDuplicateCandidateResponse;
import com.voyra.crm.dto.ClientDuplicateCheckRequest;
import com.voyra.crm.dto.ClientLookupResponse;
import com.voyra.crm.dto.ClientResponse;
import com.voyra.crm.dto.ClientUpdateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branches of {@link ClientService} the client/member rule tests leave open. */
@ExtendWith(MockitoExtension.class)
class ClientServiceCoverageTest {

    @Mock private ClientRepository clientRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private VisaRepository visaRepository;
    @Mock private ClientInvoiceRepository clientInvoiceRepository;
    @Mock private MemberMapper memberMapper;
    @Mock private AuditService auditService;
    @Mock private ClientCreditService clientCreditService;
    @InjectMocks private ClientService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private static Client client(String id, ClientType type, String identifier, String name) {
        return Client.builder().id(id).type(type).identifier(identifier).name(name).isActive(true).build();
    }

    private static ClientCreditResponse credit(String used, String available) {
        return ClientCreditResponse.builder().used(new BigDecimal(used)).available(new BigDecimal(available)).build();
    }

    // ---------------------------------------------------------------- create

    @Test
    void aCorporateClientWithALowercaseGstinIsStoredUpperCaseAndOverseasDefaultsFalse() {
        as("A1", UserType.AGENT);
        ClientCreateRequest r = new ClientCreateRequest();
        r.setIdentifier("acme-corp");
        r.setName("Acme Pvt Ltd");
        r.setType(ClientType.B2B);
        r.setPrimaryMemberName("Contact Person");
        r.setGstin(" 27abcde1234f1z5 ");
        r.setStateCode("27");
        r.setCreditLimit(new BigDecimal("50000"));
        when(clientCreditService.credit(any())).thenReturn(credit("1000", "49000"));

        ClientDetailResponse out = service.createClient(r);

        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(saved.capture());
        assertThat(saved.getValue().getGstin()).isEqualTo("27ABCDE1234F1Z5");
        assertThat(saved.getValue().getIsOverseas()).isFalse();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("A1");
        assertThat(out.getCreditUsed()).isEqualByComparingTo("1000");
        assertThat(out.getCreditAvailable()).isEqualByComparingTo("49000");
        verify(clientRepository, never()).findByPhoneSuffix(anyString());
        verify(auditService).recordCreate(AuditEntityType.CLIENT, saved.getValue().getId(), "Acme Pvt Ltd");
    }

    @Test
    void anIndividualWithoutAGstinAndAnOverseasFlagIsCreatedWithNoCreditFigures() {
        as("O1", UserType.AGENCY_OWNER);
        ClientCreateRequest r = new ClientCreateRequest();
        r.setIdentifier("9100000101");
        r.setName("Pax Person");
        r.setType(ClientType.B2C);
        r.setPrimaryMemberPhone("9100000101");
        r.setIsOverseas(true);
        when(clientRepository.findByPhoneSuffix("9100000101")).thenReturn(List.of());

        ClientDetailResponse out = service.createClient(r);

        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(saved.capture());
        assertThat(saved.getValue().getGstin()).isNull();
        assertThat(saved.getValue().getIsOverseas()).isTrue();
        assertThat(saved.getValue().getCreditLimit()).isNull();
        assertThat(out.getCreditUsed()).isNull();
        assertThat(out.getCreditAvailable()).isNull();
    }

    @Test
    void anIndividualWithNoIdentifierYetSkipsThePhoneDuplicateCheck() {
        as("O1", UserType.AGENCY_OWNER);
        ClientCreateRequest r = new ClientCreateRequest();
        r.setName("Walk In");
        r.setType(ClientType.B2C);

        service.createClient(r);

        verify(clientRepository, never()).findByPhoneSuffix(any());
        verify(clientRepository).save(any(Client.class));
    }

    @Test
    void withNoSignedInUserACreateWithoutALimitIsRefused() {
        ClientCreateRequest r = new ClientCreateRequest();
        r.setIdentifier("acme");
        r.setType(ClientType.B2B);
        assertThatThrownBy(() -> service.createClient(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enter the client's credit limit (0 if they pay upfront)");
        verify(clientRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- lists / lookups

    @Test
    void listFiltersByTypeAndAttachesCredit() {
        Client b2c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        Client b2b = client("C2", ClientType.B2B, "acme", "Acme");
        when(clientRepository.findAll()).thenReturn(List.of(b2c, b2b));
        when(clientCreditService.creditFor(List.of(b2b))).thenReturn(Map.of("C2", credit("10", "90")));
        when(clientCreditService.creditFor(List.of(b2c, b2b))).thenReturn(Map.of());
        when(memberRepository.countByClientIdAndIsActiveTrue("C2")).thenReturn(3L);

        List<ClientResponse> corp = service.listClients(ClientType.B2B);
        assertThat(corp).extracting(ClientResponse::getId).containsExactly("C2");
        assertThat(corp.get(0).getMemberCount()).isEqualTo(3);
        assertThat(corp.get(0).getCreditUsed()).isEqualByComparingTo("10");
        assertThat(corp.get(0).getCreditAvailable()).isEqualByComparingTo("90");

        List<ClientResponse> all = service.listClients((ClientType) null);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getCreditUsed()).isNull();
    }

    @Test
    void pagedListUsesTheTypedQueryOnlyWhenFiltered() {
        PageRequest p = PageRequest.of(0, 10);
        Client c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        when(clientRepository.findAll(p)).thenReturn(new PageImpl<>(List.of(c), p, 1));
        when(clientRepository.findByType(ClientType.B2B, p)).thenReturn(new PageImpl<>(List.of(), p, 0));
        when(clientCreditService.creditFor(List.of(c))).thenReturn(Map.of("C1", credit("0", "0")));

        PagedResponse<ClientResponse> all = service.listClients(null, p);
        assertThat(all.getContent()).extracting(ClientResponse::getId).containsExactly("C1");
        assertThat(service.listClients(ClientType.B2B, p).getContent()).isEmpty();
    }

    @Test
    void getAndCreditNeedAnExistingClient() {
        Client c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(clientRepository.findById("C9")).thenReturn(Optional.empty());
        ClientCreditResponse cr = credit("5", "95");
        when(clientCreditService.credit(c)).thenReturn(cr);

        assertThat(service.getClient("C1").getName()).isEqualTo("Pax Person");
        assertThat(service.getCredit("C1")).isSameAs(cr);
        assertThatThrownBy(() -> service.getClient("C9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Client not found: C9");
    }

    @Test
    void lookupReportsAFoundClientWithItsMemberCountOrNotFound() {
        when(clientRepository.findByIdentifierAndIsActiveTrue("9100000101"))
                .thenReturn(Optional.of(client("C1", ClientType.B2C, "9100000101", "Pax Person")));
        when(memberRepository.countByClientIdAndIsActiveTrue("C1")).thenReturn(2L);
        when(clientRepository.findByIdentifierAndIsActiveTrue("nobody")).thenReturn(Optional.empty());

        ClientLookupResponse found = service.lookupByIdentifier("9100000101");
        assertThat(found.isFound()).isTrue();
        assertThat(found.getId()).isEqualTo("C1");
        assertThat(found.getMemberCount()).isEqualTo(2);
        ClientLookupResponse missing = service.lookupByIdentifier("nobody");
        assertThat(missing.isFound()).isFalse();
        assertThat(missing.getIdentifier()).isEqualTo("nobody");
        assertThat(missing.getId()).isNull();
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    void aPhoneIdentifierInAnotherFormatIsAnExactMatchAndSimilarNamesAreAdvisory() {
        Client exact = client("C1", ClientType.B2C, "+91 91000 00101", "Pax Person");
        Client sameSuffix = client("C2", ClientType.B2C, "9100000101", "Pax P");
        Client similar = client("C3", ClientType.B2C, "9000000000", "Person Pax");
        Client unlike = client("C4", ClientType.B2C, "9000000001", "Someone Else Entirely");
        ClientDuplicateCheckRequest r = new ClientDuplicateCheckRequest();
        r.setIdentifier("+91 91000 00101");
        r.setName("Pax Person");
        when(clientRepository.findByIdentifierAndIsActiveTrue("+91 91000 00101")).thenReturn(Optional.of(exact));
        when(clientRepository.findByPhoneSuffix("+91 91000 00101")).thenReturn(List.of(exact, sameSuffix));
        when(clientRepository.findSimilarByName("Pax Person")).thenReturn(List.of(sameSuffix, similar, unlike));

        List<ClientDuplicateCandidateResponse> out = service.checkDuplicates(r);

        assertThat(out).extracting(ClientDuplicateCandidateResponse::getId).containsExactly("C1", "C2", "C3");
        assertThat(out).extracting(ClientDuplicateCandidateResponse::getMatchReason)
                .containsExactly("EXACT_IDENTIFIER", "EXACT_IDENTIFIER", "NAME_SIMILARITY");
    }

    @Test
    void aCorporateHandleWithDigitsIsOnlyAPhoneSuffixMatchAndBlankInputsSearchNothing() {
        ClientDuplicateCheckRequest r = new ClientDuplicateCheckRequest();
        r.setIdentifier("acme9100000101");
        r.setName(" ");
        when(clientRepository.findByIdentifierAndIsActiveTrue("acme9100000101")).thenReturn(Optional.empty());
        when(clientRepository.findByPhoneSuffix("acme9100000101")).thenReturn(List.of(
                client("C2", ClientType.B2C, "9100000101", "Pax P")));

        assertThat(service.checkDuplicates(r)).extracting(ClientDuplicateCandidateResponse::getMatchReason)
                .containsExactly("PHONE_SUFFIX");
        verify(clientRepository, never()).findSimilarByName(anyString());

        ClientDuplicateCheckRequest blank = new ClientDuplicateCheckRequest();
        blank.setIdentifier("");
        assertThat(service.checkDuplicates(blank)).isEmpty();
        assertThat(service.checkDuplicates(new ClientDuplicateCheckRequest())).isEmpty();
    }

    // ---------------------------------------------------------------- update

    @Test
    void changingTheIdentifierToOneInUseIsRefused() {
        Client c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(clientRepository.existsByIdentifierAndIsActiveTrue("9100000202")).thenReturn(true);
        ClientUpdateRequest r = new ClientUpdateRequest();
        r.setIdentifier("9100000202");

        assertThatThrownBy(() -> service.updateClient("C1", r))
                .isInstanceOf(IllegalStateException.class).hasMessage("A client already exists with identifier 9100000202");
        verify(clientRepository, never()).save(any());
    }

    @Test
    void aNewPhoneHeldByAnotherIndividualIsRefusedButTheClientsOwnRowIsIgnored() {
        as("O1", UserType.AGENCY_OWNER);
        Client c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(clientRepository.findByPhoneSuffix("+91 9100000202")).thenReturn(List.of(client("C1", ClientType.B2C, "x", "x")));
        when(clientRepository.findByPhoneSuffix("9100000303")).thenReturn(List.of(client("C7", ClientType.B2C, "x", "x")));
        ClientUpdateRequest ok = new ClientUpdateRequest();
        ok.setIdentifier("+91 9100000202");

        service.updateClient("C1", ok);
        assertThat(c.getIdentifier()).isEqualTo("+91 9100000202");

        ClientUpdateRequest taken = new ClientUpdateRequest();
        taken.setIdentifier("9100000303");
        assertThatThrownBy(() -> service.updateClient("C1", taken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A client already exists with this phone number (9100000303)");
    }

    @Test
    void switchingToCorporateWithANewHandleSkipsThePhoneRulesAndSetsEveryField() {
        as("O1", UserType.AGENCY_OWNER);
        Client c = client("C1", ClientType.B2C, "9100000101", "Pax Person");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        ClientUpdateRequest r = new ClientUpdateRequest();
        r.setIdentifier("acme");
        r.setType(ClientType.B2B);
        r.setName("Acme Pvt Ltd");
        r.setGstin("29abcde1234f1z5");
        r.setStateCode("29");
        r.setBillingAddress("1 MG Road");
        r.setIsOverseas(false);
        r.setCreditLimit(new BigDecimal("25000"));

        ClientDetailResponse out = service.updateClient("C1", r);

        assertThat(out.getType()).isEqualTo(ClientType.B2B);
        assertThat(out.getIdentifier()).isEqualTo("acme");
        assertThat(c.getGstin()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(c.getStateCode()).isEqualTo("29");
        assertThat(c.getBillingAddress()).isEqualTo("1 MG Road");
        assertThat(c.getIsOverseas()).isFalse();
        assertThat(c.getCreditLimit()).isEqualByComparingTo("25000");
        assertThat(c.getModifiedBy()).isEqualTo("O1");
        verify(clientRepository, never()).findByPhoneSuffix(anyString());
        // renamed: the snapshots follow, but a B2B primary member is left alone
        verify(memberRepository, never()).findByClientIdAndTypeAndIsActiveTrue(anyString(), any());
        verify(leadRepository).updateClientNameForClient("C1", "Acme Pvt Ltd");
        verify(bookingRepository).updateClientNameForClient("C1", "Acme Pvt Ltd");
        verify(visaRepository).updateClientNameForClient("C1", "Acme Pvt Ltd");
        verify(clientInvoiceRepository).updateClientNameForClient("C1", "Acme Pvt Ltd");
        verify(auditService).recordUpdate(eq(AuditEntityType.CLIENT), eq("C1"), eq("Acme Pvt Ltd"), anyList());
    }

    @Test
    void anIndividualMustKeepAPhoneIdentifierWhenItsTypeIsResent() {
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        ClientUpdateRequest r = new ClientUpdateRequest();
        r.setType(ClientType.B2C);

        assertThatThrownBy(() -> service.updateClient("C1", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid phone number");
    }

    @Test
    void sendingTheSameIdentifierAndNameChangesNothingAndClearingTheLimitIsTheOwnersCall() {
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        c.setCreditLimit(new BigDecimal("1000"));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        ClientUpdateRequest r = new ClientUpdateRequest();
        r.setIdentifier("acme");
        r.setName("Acme");
        r.setNoCreditLimit(true);

        as("A1", UserType.AGENT);
        assertThatThrownBy(() -> service.updateClient("C1", r))
                .isInstanceOf(AccessDeniedException.class).hasMessage("Only the owner can change a client's credit limit");

        as("O1", UserType.AGENCY_OWNER);
        service.updateClient("C1", r);
        assertThat(c.getCreditLimit()).isNull();
        verify(clientRepository, never()).existsByIdentifierAndIsActiveTrue(anyString());
        verifyNoInteractions(leadRepository, bookingRepository, visaRepository, clientInvoiceRepository);
    }

    @Test
    void aStateCodeAloneIsCheckedAgainstTheStoredGstin() {
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        c.setGstin("27ABCDE1234F1Z5");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        ClientUpdateRequest r = new ClientUpdateRequest();
        r.setStateCode("29");

        assertThatThrownBy(() -> service.updateClient("C1", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("registered in state 27");
    }

    // ---------------------------------------------------------------- status

    @Test
    void deactivatingAClientWithOpenLeadsIsRefused() {
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(leadRepository.countByClientIdAndStatusNotIn("C1", LeadService.TERMINAL_STATUSES)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateStatus("C1", false))
                .isInstanceOf(IllegalStateException.class).hasMessage("This client still has open leads - close or reassign them first");
        assertThat(c.getIsActive()).isTrue();
    }

    @Test
    void deactivatingWithNoOpenLeadsAndReactivatingAreAudited() {
        as("O1", UserType.AGENCY_OWNER);
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(leadRepository.countByClientIdAndStatusNotIn("C1", LeadService.TERMINAL_STATUSES)).thenReturn(0L);

        assertThat(service.updateStatus("C1", false).getIsActive()).isFalse();
        assertThat(service.updateStatus("C1", true).getIsActive()).isTrue();
        verify(leadRepository, org.mockito.Mockito.times(1)).countByClientIdAndStatusNotIn(any(), any());
        verify(auditService, org.mockito.Mockito.times(2)).recordUpdate(eq(AuditEntityType.CLIENT), eq("C1"), eq("Acme"), anyList());
    }

    @Test
    void reactivatingOverAnIdentifierNowTakenIsAConflict() {
        as("O1", UserType.AGENCY_OWNER);
        Client c = client("C1", ClientType.B2B, "acme", "Acme");
        c.setIsActive(false);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(c));
        when(clientRepository.save(c)).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> service.updateStatus("C1", true))
                .isInstanceOf(IllegalStateException.class).hasMessage("Another active client already uses identifier acme");
        verifyNoInteractions(auditService);
    }
}
