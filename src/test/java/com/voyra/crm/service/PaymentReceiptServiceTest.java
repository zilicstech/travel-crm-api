package com.voyra.crm.service;

import com.voyra.crm.dto.PaymentReceiptApplyAdvanceRequest;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptServiceTest {

    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private ClientService clientService;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;
    @Mock
    private CustomerLedgerService customerLedgerService;
    @Mock
    private BookingAccountingSync bookingAccountingSync;
    @Mock
    private JournalService journalService;
    @Mock
    private com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;

    @InjectMocks
    private PaymentReceiptService paymentReceiptService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Invoice issuedInvoice(BigDecimal grandTotal) {
        return Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).clientId("K1").bookingId("B1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .fxRateSource(FxRateSource.INR_IDENTITY).grandTotal(grandTotal).grandTotalInr(grandTotal)
                .amountReceived(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(grandTotal).balanceDueInr(grandTotal).build();
    }

    private PaymentReceiptRequest request(BigDecimal amount) {
        PaymentReceiptRequest r = new PaymentReceiptRequest();
        r.setInvoiceId("I1");
        r.setAmount(amount);
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setReceivedOn(LocalDate.of(2026, 9, 19));
        return r;
    }

    @Test
    void recordingAgainstADraftIsRejected() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> paymentReceiptService.record(request(new BigDecimal("100"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPartialReceiptMovesTheInvoiceToPartiallyPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("400.00")).build()));

        PaymentReceiptResponse response = paymentReceiptService.record(request(new BigDecimal("400.00")));

        assertThat(response.getReceiptNumber()).isEqualTo("RCP/2026-27/0001");
        assertThat(response.getAmountInr()).isEqualByComparingTo("400.00");
        assertThat(response.getIsAdvance()).isFalse();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("400.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("600.00");
    }

    @Test
    void recordingAgainstAnInvoicePostsTheReceiptAgainstInvoiceJournal() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("400.00")).build()));

        paymentReceiptService.record(request(new BigDecimal("400.00")));

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.RECEIPT_AGAINST_INVOICE);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1110"); // BANK_ACCOUNTS - request() uses BANK_TRANSFER
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("400.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1200"); // ACCOUNTS_RECEIVABLE
        assertThat(posting.lines().get(1).partyType()).isEqualTo("CLIENT");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("K1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void aReceiptThatClearsTheBalanceMovesTheInvoiceToPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        paymentReceiptService.record(request(new BigDecimal("1000.00")));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void anAdvanceReceiptAgainstAProformaNeverChangesItsStatus() {
        Invoice proforma = Invoice.builder().id("I1").documentType(InvoiceDocumentType.PROFORMA)
                .status(InvoiceLifecycle.PROFORMA_ISSUED).clientId("K1").currencyCode("INR")
                .fxRateToInr(BigDecimal.ONE).grandTotal(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");

        PaymentReceiptResponse response = paymentReceiptService.record(request(new BigDecimal("300.00")));

        assertThat(response.getIsAdvance()).isTrue();
        assertThat(proforma.getStatus()).isEqualTo(InvoiceLifecycle.PROFORMA_ISSUED);
        org.mockito.Mockito.verify(invoiceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void reversingAnAlreadyReversedReceiptIsRejected() {
        PaymentReceipt already = PaymentReceipt.builder().id("R1").invoiceId("I1")
                .reversedAt(java.time.LocalDateTime.now()).build();
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(already));

        assertThatThrownBy(() -> paymentReceiptService.reverse("R1", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    void reversingAReversalIsRejected() {
        PaymentReceipt reversal = PaymentReceipt.builder().id("R2").invoiceId("I1").reversesReceiptId("R1").build();
        when(paymentReceiptRepository.findById("R2")).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> paymentReceiptService.reverse("R2", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reverse a reversing entry");
    }

    @Test
    void reversingARecordedReceiptWritesAnOppositeSignedRowAndRecomputesTheInvoice() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        invoice.setAmountReceived(new BigDecimal("400.00"));
        invoice.setBalanceDue(new BigDecimal("600.00"));
        invoice.setStatus(InvoiceLifecycle.PARTIALLY_PAID);
        PaymentReceipt original = PaymentReceipt.builder().id("R1").invoiceId("I1").clientId("K1")
                .direction(ReceiptDirection.RECEIPT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("400.00")).amountInr(new BigDecimal("400.00"))
                .paymentMode(PaymentMode.UPI).isAdvance(false).build();
        when(paymentReceiptRepository.findById("R1")).thenReturn(Optional.of(original));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.now())).thenReturn("RCP/2026-27/0002");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of()); // net zero after reversal

        PaymentReceiptResponse reversal = paymentReceiptService.reverse("R1", "wrong invoice");

        assertThat(reversal.getAmount()).isEqualByComparingTo("-400.00");
        assertThat(reversal.getReversesReceiptId()).isEqualTo("R1");
        assertThat(original.getReversedAt()).isNotNull();
        assertThat(original.getReversalReason()).isEqualTo("wrong invoice");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("0.00");
    }

    @Test
    void recordAuditsAsAPaymentReceiptCreate() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(any(), any())).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());

        paymentReceiptService.record(request(new BigDecimal("100.00")));

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(auditService).recordCreate(
                org.mockito.ArgumentMatchers.eq(com.voyra.crm.enums.AuditEntityType.PAYMENT_RECEIPT),
                idCaptor.capture(), org.mockito.ArgumentMatchers.eq("RCP/2026-27/0001"));
    }

    @Test
    void recordingWithNoInvoiceIdRequiresAClientId() {
        PaymentReceiptRequest deposit = new PaymentReceiptRequest();
        deposit.setAmount(new BigDecimal("500000.00"));
        deposit.setPaymentMode(PaymentMode.BANK_TRANSFER);
        deposit.setReceivedOn(LocalDate.of(2026, 9, 19));

        assertThatThrownBy(() -> paymentReceiptService.record(deposit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void aDepositWithNoInvoiceIsRecordedAsAnAdvanceOnTheClient() {
        when(clientService.findAccessibleClient("K1")).thenReturn(Client.builder().id("K1").name("Rakesh Verma").build());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");

        PaymentReceiptRequest deposit = new PaymentReceiptRequest();
        deposit.setClientId("K1");
        deposit.setAmount(new BigDecimal("500000.00"));
        deposit.setPaymentMode(PaymentMode.BANK_TRANSFER);
        deposit.setReceivedOn(LocalDate.of(2026, 9, 19));

        PaymentReceiptResponse response = paymentReceiptService.record(deposit);

        assertThat(response.getInvoiceId()).isNull();
        assertThat(response.getClientId()).isEqualTo("K1");
        assertThat(response.getIsAdvance()).isTrue();
        assertThat(response.getAmountInr()).isEqualByComparingTo("500000.00");
        org.mockito.Mockito.verify(invoiceRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void recordingADepositPostsTheReceiptAdvanceJournal() {
        when(clientService.findAccessibleClient("K1")).thenReturn(Client.builder().id("K1").name("Rakesh Verma").build());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");

        PaymentReceiptRequest deposit = new PaymentReceiptRequest();
        deposit.setClientId("K1");
        deposit.setAmount(new BigDecimal("500000.00"));
        deposit.setPaymentMode(PaymentMode.BANK_TRANSFER);
        deposit.setReceivedOn(LocalDate.of(2026, 9, 19));

        paymentReceiptService.record(deposit);

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.RECEIPT_ADVANCE);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1110"); // BANK_ACCOUNTS
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("500000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("2110"); // CLIENT_ADVANCES_HELD
        assertThat(posting.lines().get(1).partyType()).isEqualTo("CLIENT");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("K1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("500000.00");
    }

    @Test
    void applyingMoreThanTheWalletHoldsIsRejected() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        PaymentReceipt deposit = PaymentReceipt.builder().id("D1").clientId("K1").invoiceId(null)
                .isAdvance(true).amountInr(new BigDecimal("300.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of());

        PaymentReceiptApplyAdvanceRequest request = new PaymentReceiptApplyAdvanceRequest();
        request.setAdvanceReceiptId("D1");
        request.setAmount(new BigDecimal("400.00"));

        assertThatThrownBy(() -> paymentReceiptService.applyAdvance("I1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("300.00");
    }

    @Test
    void applyingTheWalletSettlesTheInvoiceWithNoLedgerPost() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        PaymentReceipt deposit = PaymentReceipt.builder().id("D1").clientId("K1").invoiceId(null)
                .isAdvance(true).amountInr(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("400.00")).build()));

        PaymentReceiptApplyAdvanceRequest request = new PaymentReceiptApplyAdvanceRequest();
        request.setAdvanceReceiptId("D1");
        request.setAmount(new BigDecimal("400.00"));

        PaymentReceiptResponse response = paymentReceiptService.applyAdvance("I1", request);

        assertThat(response.getAppliedFromAdvance()).isTrue();
        assertThat(response.getAmount()).isEqualByComparingTo("400.00");
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("400.00");
        org.mockito.Mockito.verify(customerLedgerService, org.mockito.Mockito.never()).post(any());
    }

    @Test
    void applyingTheWalletPostsTheAdvanceAppliedJournal() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        PaymentReceipt deposit = PaymentReceipt.builder().id("D1").clientId("K1").invoiceId(null)
                .isAdvance(true).amountInr(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(deposit));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("400.00")).build()));

        PaymentReceiptApplyAdvanceRequest request = new PaymentReceiptApplyAdvanceRequest();
        request.setAdvanceReceiptId("D1");
        request.setAmount(new BigDecimal("400.00"));

        paymentReceiptService.applyAdvance("I1", request);

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.ADVANCE_APPLIED);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2110"); // CLIENT_ADVANCES_HELD
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("400.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1200"); // ACCOUNTS_RECEIVABLE
        assertThat(posting.lines().get(1).partyType()).isEqualTo("CLIENT");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("K1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void issuingAnInvoiceAutoAppliesWhateverTheClientsWalletHolds() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("D1").clientId("K1")
                        .isAdvance(true).amountInr(new BigDecimal("5000.00")).build()));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        paymentReceiptService.applyAvailableWallet(invoice);

        // 5000 held, only 1000 owed - draws exactly the balance, not the whole wallet.
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("1000.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
    }
}
