package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.ClientInvoiceResponse;
import com.voyra.crm.dto.InvoiceSummaryResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceCoverageTest {

    @Mock private ClientInvoiceRepository clientInvoiceRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private SupplierLedgerService supplierLedgerService;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuditService auditService;

    @InjectMocks private InvoiceService service;

    @BeforeEach
    void owner() {
        as("O1", UserType.AGENCY_OWNER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "u", type, "T1"), null, List.of()));
    }

    private static ClientInvoiceCreateRequest request() {
        ClientInvoiceCreateRequest r = new ClientInvoiceCreateRequest();
        r.setClientId("C1");
        r.setAgentId("A1");
        r.setAmount(new BigDecimal("1000"));
        return r;
    }

    private void agentInAgency() {
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(Agent.builder().id("A1").build()));
    }

    private void client() {
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
    }

    private static ClientInvoice.ClientInvoiceBuilder invoice() {
        return ClientInvoice.builder().id("CI1").clientName("Asha").totalWithGst(new BigDecimal("1180")).amountPaid(BigDecimal.ZERO)
                .gst(new BigDecimal("180")).status(InvoiceStatus.PENDING);
    }

    @Test
    void anOwnerMustNameAnAgentOfTheAgency() {
        ClientInvoiceCreateRequest r = request();
        r.setAgentId(null);
        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("agentId is required when an Owner creates a client invoice");
        r.setAgentId(" ");
        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("agentId is required when an Owner creates a client invoice");
        r.setAgentId("A9");
        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found in this agency: A9");
        verifyNoInteractions(clientRepository);
    }

    @Test
    void anInvoiceNeedsAKnownClient() {
        agentInAgency();
        assertThatThrownBy(() -> service.createClientInvoice(request()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Client not found: C1");
    }

    @Test
    void anInvoiceOnALeadServiceNamesTheServiceAndTellsTheLead() {
        agentInAgency();
        client();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("A2").build()));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(LeadService.builder().id("S1").label("Goa flights").build()));
        ClientInvoiceCreateRequest r = request();
        r.setLeadId("L1");
        r.setServiceId("S1");
        r.setDescription("Tickets");
        r.setGstRate(new BigDecimal("5"));
        r.setPaymentMode("UPI");

        ClientInvoiceResponse response = service.createClientInvoice(r);

        assertThat(response.getServiceLabel()).isEqualTo("Goa flights");
        assertThat(response.getAgentId()).isEqualTo("A1");
        assertThat(response.getGst()).isEqualByComparingTo("50.00");
        assertThat(response.getTotalWithGst()).isEqualByComparingTo("1050.00");
        assertThat(response.getPending()).isEqualByComparingTo("1050.00");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.INVOICE_ADDED, "Invoice raised: Tickets");
        verify(auditService).recordCreate(AuditEntityType.CLIENT_INVOICE, response.getId(), "Asha / Tickets");
    }

    @Test
    void anInvoiceOnALeadWithoutAServiceOrDescription() {
        agentInAgency();
        client();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("A2").build()));
        ClientInvoiceCreateRequest r = request();
        r.setLeadId("L1");

        ClientInvoiceResponse response = service.createClientInvoice(r);

        assertThat(response.getServiceLabel()).isNull();
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.INVOICE_ADDED, "Invoice raised");
        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordCreate(eq(AuditEntityType.CLIENT_INVOICE), eq(response.getId()), label.capture());
        assertThat(label.getValue()).isEqualTo("Asha / " + response.getId());
        verifyNoInteractions(leadServiceRepository);
    }

    @Test
    void anUnknownLeadOrServiceIsRefused() {
        agentInAgency();
        client();
        ClientInvoiceCreateRequest r = request();
        r.setLeadId("L9");
        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: L9");

        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("A2").build()));
        r.setLeadId("L1");
        r.setServiceId("S9");
        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service not found: S9");
        verify(clientInvoiceRepository, never()).save(any());
    }

    @Test
    void anAgentMayInvoiceTheirOwnLeadOrOneTheyServe() {
        as("A1", UserType.AGENT);
        client();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("A1").build()));
        when(leadRepository.findById("L2")).thenReturn(Optional.of(Lead.builder().id("L2").createdBy("A2").build()));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").manageableServices(List.of()).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L2"))
                .thenReturn(List.of(LeadService.builder().assignedAgentId("A1").type(ServiceType.HOTEL).build()));
        ClientInvoiceCreateRequest own = request();
        own.setAgentId(null);
        own.setLeadId("L1");
        ClientInvoiceCreateRequest served = request();
        served.setLeadId("L2");

        assertThat(service.createClientInvoice(own).getAgentId()).isEqualTo("A1");
        assertThat(service.createClientInvoice(served).getAgentId()).isEqualTo("A1");
    }

    @Test
    void anAgentCannotInvoiceSomeoneElsesLead() {
        as("A1", UserType.AGENT);
        client();
        when(leadRepository.findById("L2")).thenReturn(Optional.of(Lead.builder().id("L2").createdBy("A2").build()));
        ClientInvoiceCreateRequest r = request();
        r.setLeadId("L2");

        assertThatThrownBy(() -> service.createClientInvoice(r))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This lead is not accessible to you");
    }

    @Test
    void aLeadsInvoicesAreListedNewestFirst() {
        when(clientInvoiceRepository.findByLeadIdOrderByInvoiceDateDesc("L1")).thenReturn(List.of(invoice().build()));
        assertThat(service.listForLead("L1")).extracting(ClientInvoiceResponse::getId).containsExactly("CI1");
    }

    @Test
    void anAgentListsOnlyTheirOwnInvoicesAndAnOwnerAll() {
        when(clientInvoiceRepository.findAll()).thenReturn(List.of(invoice().build()));
        assertThat(service.listClientInvoices()).hasSize(1);
        PageRequest page = PageRequest.of(0, 5);
        when(clientInvoiceRepository.findAll(page)).thenReturn(new PageImpl<>(List.of(invoice().build()), page, 1));
        PagedResponse<ClientInvoiceResponse> all = service.listClientInvoices(page);
        assertThat(all.getContent()).extracting(ClientInvoiceResponse::getId).containsExactly("CI1");

        as("A1", UserType.AGENT);
        when(clientInvoiceRepository.findByAgentId("A1")).thenReturn(List.of());
        assertThat(service.listClientInvoices()).isEmpty();
        when(clientInvoiceRepository.findByAgentId("A1", page)).thenReturn(new PageImpl<>(List.of(), page, 0));
        assertThat(service.listClientInvoices(page).getContent()).isEmpty();
    }

    @Test
    void aPaymentSetsTheStatusFromWhatIsPaid() {
        ClientInvoice inv = invoice().paymentMode("CASH").build();
        when(clientInvoiceRepository.findById("CI1")).thenReturn(Optional.of(inv));
        ClientInvoicePaymentRequest r = new ClientInvoicePaymentRequest();

        r.setAmountPaid(new BigDecimal("500"));
        r.setPaymentMode("UPI");
        assertThat(service.recordPayment("CI1", r).getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(inv.getPaymentMode()).isEqualTo("UPI");

        r.setPaymentMode(null);
        r.setAmountPaid(new BigDecimal("1180"));
        ClientInvoiceResponse paid = service.recordPayment("CI1", r);
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.getPending()).isEqualByComparingTo("0");
        assertThat(inv.getPaymentMode()).isEqualTo("UPI");

        r.setAmountPaid(BigDecimal.ZERO);
        assertThat(service.recordPayment("CI1", r).getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(inv.getUpdatedBy()).isEqualTo("O1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService, org.mockito.Mockito.times(3)).recordUpdate(eq(AuditEntityType.CLIENT_INVOICE), eq("CI1"), eq("Asha / CI1"), changes.capture());
        assertThat(changes.getAllValues().get(0)).extracting(AuditChange::getField).contains("amountPaid", "status", "paymentMode");
    }

    @Test
    void aPaymentOnAnUnknownInvoiceIsRefused() {
        ClientInvoicePaymentRequest r = new ClientInvoicePaymentRequest();
        r.setAmountPaid(BigDecimal.ONE);
        assertThatThrownBy(() -> service.recordPayment("CI9", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invoice not found: CI9");
    }

    @Test
    void theOwnersSummaryAddsTheSupplierFigures() {
        when(clientInvoiceRepository.findAll()).thenReturn(List.of(
                invoice().amountPaid(new BigDecimal("180")).build(),
                invoice().id("CI2").totalWithGst(new BigDecimal("590")).gst(new BigDecimal("90")).amountPaid(new BigDecimal("590")).build()));
        when(supplierLedgerService.totalPaidAllVendors()).thenReturn(new BigDecimal("700"));
        when(supplierLedgerService.totalPayableAllVendors()).thenReturn(new BigDecimal("300"));

        InvoiceSummaryResponse s = service.getSummary();

        assertThat(s.getTotalCollected()).isEqualByComparingTo("770");
        assertThat(s.getTotalPendingToCollect()).isEqualByComparingTo("1000");
        assertThat(s.getTotalGst()).isEqualByComparingTo("270");
        assertThat(s.getTotalPaidToSuppliers()).isEqualByComparingTo("700");
        assertThat(s.getTotalPendingToPay()).isEqualByComparingTo("300");
    }

    @Test
    void anAgentsSummaryHasNoSupplierFigures() {
        as("A1", UserType.AGENT);
        when(clientInvoiceRepository.findByAgentId("A1")).thenReturn(List.of(invoice().build()));

        InvoiceSummaryResponse s = service.getSummary();

        assertThat(s.getTotalPendingToCollect()).isEqualByComparingTo("1180");
        assertThat(s.getTotalPaidToSuppliers()).isEqualByComparingTo("0");
        assertThat(s.getTotalPendingToPay()).isEqualByComparingTo("0");
        verifyNoInteractions(supplierLedgerService);
    }

    @Test
    void anInvoiceIsOverdueOnlyPastItsDueDateWithMoneyStillOwed() {
        LocalDate past = LocalDate.now().minusDays(1);
        when(clientInvoiceRepository.findAll()).thenReturn(List.of(
                invoice().id("late").dueDate(past).build(),
                invoice().id("settled").dueDate(past).amountPaid(new BigDecimal("1180")).build(),
                invoice().id("future").dueDate(LocalDate.now().plusDays(1)).build(),
                invoice().id("undated").build()));

        assertThat(service.listClientInvoices()).extracting(ClientInvoiceResponse::isOverdue)
                .containsExactly(true, false, false, false);
    }
}
