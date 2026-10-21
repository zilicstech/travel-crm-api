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
    @Mock
    private InvoiceLineSource invoiceLineSource;
    @Mock
    private com.voyra.crm.repository.BookingRepository bookingRepository;
    @Mock
    private com.voyra.crm.repository.CreditNoteRepository creditNoteRepository;

    @org.mockito.Spy

    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);


    @InjectMocks
    private PaymentReceiptService paymentReceiptService;

    @BeforeEach
    void authenticateAsAccountant() {
        TestInvoiceLines.into(paymentReceiptService, invoiceRepository, null, null);
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

    /** An invoice billing two bookings: each keeps its own account, so a part payment must say which booking it is for. */
    @Test
    void aPartPaymentOnAnInvoiceOfSeveralBookingsMustNameTheBooking() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
        when(invoiceLineSource.bookingIdsOf(any())).thenReturn(List.of("B1", "B2"));
        org.springframework.test.util.ReflectionTestUtils.setField(paymentReceiptService, "invoiceLineSource", invoiceLineSource);

        assertThatThrownBy(() -> paymentReceiptService.record(request(new BigDecimal("400.00"))))
                .hasMessageContaining("pick the booking this payment is for");
        PaymentReceiptRequest other = request(new BigDecimal("400.00"));
        other.setBookingId("B9");
        assertThatThrownBy(() -> paymentReceiptService.record(other)).hasMessageContaining("isn't on invoice");
    }

    /** A payment named for one booking of a combined invoice can't settle more than that booking owes - the rest would read as paid on the other. */
    @Test
    void aPaymentNamedForOneBookingIsCappedAtWhatThatBookingOwes() {
        Invoice invoice = issuedInvoice(new BigDecimal("17860.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
        when(invoiceLineSource.bookingIdsOf(any())).thenReturn(List.of("B1", "B2"));
        org.springframework.test.util.ReflectionTestUtils.setField(paymentReceiptService, "invoiceLineSource", invoiceLineSource);
        when(bookingAccountingSync.positionOn(invoice, "B2", null))
                .thenReturn(new BookingAccountingSync.Position(new BigDecimal("11180.00"), BigDecimal.ZERO));

        PaymentReceiptRequest tooMuch = request(new BigDecimal("17860.00"));
        tooMuch.setBookingId("B2");
        assertThatThrownBy(() -> paymentReceiptService.record(tooMuch)).hasMessageContaining("owes only 11180.00");
        org.mockito.Mockito.verify(paymentReceiptRepository, org.mockito.Mockito.never()).save(any());
    }

    /** One booking cancelled with its refund still unpaid makes the invoice read paid; the other booking can still pay its own share. */
    @Test
    void theOtherBookingCanPayWhileACancelledBookingsRefundIsUnpaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("33540.00"));
        invoice.setStatus(InvoiceLifecycle.PAID);
        invoice.setBalanceDue(new BigDecimal("-9140.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
        when(invoiceLineSource.bookingIdsOf(any())).thenReturn(List.of("B1", "B2"));
        org.springframework.test.util.ReflectionTestUtils.setField(paymentReceiptService, "invoiceLineSource", invoiceLineSource);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", com.voyra.crm.enums.CreditNoteStatus.ISSUED)).thenReturn(List.of(
                com.voyra.crm.entity.CreditNote.builder().refundableAmount(new BigDecimal("17680.00")).refundedAmount(BigDecimal.ZERO).build()));
        when(bookingAccountingSync.positionOn(invoice, "B2", null))
                .thenReturn(new BookingAccountingSync.Position(new BigDecimal("11180.00"), new BigDecimal("2640.00")));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);

        PaymentReceiptRequest tooMuch = request(new BigDecimal("8540.01"));
        tooMuch.setBookingId("B2");
        assertThatThrownBy(() -> paymentReceiptService.record(tooMuch)).hasMessageContaining("owes only 8540.00");
        PaymentReceiptRequest share = request(new BigDecimal("8540.00"));
        share.setBookingId("B2");
        paymentReceiptService.record(share);
        org.mockito.Mockito.verify(paymentReceiptRepository).save(org.mockito.ArgumentMatchers.argThat(r -> "B2".equals(r.getBookingId())));
    }

    @Test
    void recordingAgainstADraftIsRejected() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> paymentReceiptService.record(request(new BigDecimal("100"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPartialReceiptMovesTheInvoiceToPartiallyPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
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
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
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
    void aReceiptLargerThanTheBalanceDueIsRejected() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> paymentReceiptService.record(request(new BigDecimal("1000.01"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the invoice's balance due");
        org.mockito.Mockito.verify(paymentReceiptRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aReceiptThatClearsTheBalanceMovesTheInvoiceToPaid() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
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
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(proforma));
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
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
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

    /**
     * BUG-MO-10: the client's pool is 8,850 on account + 640 held for another booking (B9) + a 1,000 deposit for B9
     * less a 320 refund paid out of a held advance (also B9) = 10,170 in the pool, of which 640 + 1,000 - 320 = 1,320
     * is ring-fenced for B9. This invoice (booking B1) may draw 10,170 - 1,320 = 8,850 - the figure the screen shows
     * and the most the apply accepts.
     */
    @Test
    void walletForAnInvoiceIsWhatItsBookingMayDrawAndTheApplyKeepsToIt() {
        Invoice invoice = issuedInvoice(new BigDecimal("20000.00"));
        PaymentReceipt onAccount = PaymentReceipt.builder().id("D1").clientId("K1").isAdvance(true).direction(ReceiptDirection.RECEIPT)
                .amountInr(new BigDecimal("8850.00")).build();
        PaymentReceipt heldElsewhere = PaymentReceipt.builder().id("D2").clientId("K1").bookingId("B9").isAdvance(true)
                .direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("1640.00")).build();
        PaymentReceipt refundedOut = PaymentReceipt.builder().id("D3").clientId("K1").bookingId("B9").isAdvance(true)
                .direction(ReceiptDirection.REFUND).amountInr(new BigDecimal("320.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.findById("D1")).thenReturn(Optional.of(onAccount));
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(onAccount, heldElsewhere, refundedOut));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());

        com.voyra.crm.dto.InvoiceWalletResponse wallet = paymentReceiptService.walletFor("I1");
        assertThat(wallet.getAvailableInr()).isEqualByComparingTo("8850.00");
        assertThat(wallet.getAdvanceReceiptId()).isEqualTo("D1");

        PaymentReceiptApplyAdvanceRequest request = new PaymentReceiptApplyAdvanceRequest();
        request.setAdvanceReceiptId("D1");
        request.setAmount(new BigDecimal("8850.01"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> paymentReceiptService.applyAdvance("I1", request))
                .hasMessageContaining("8850.00");
    }

    /** An invoice billing two bookings may draw money held for either; what it draws of each booking's advance is drawn under it. */
    @Test
    void anInvoiceOfTwoBookingsDrawsEachBookingsHeldAdvanceUnderThatBooking() {
        Invoice invoice = issuedInvoice(new BigDecimal("20000.00"));
        com.voyra.crm.repository.InvoiceLineItemRepository lines = org.mockito.Mockito.mock(com.voyra.crm.repository.InvoiceLineItemRepository.class);
        when(lines.findBookingIdsByInvoiceId("I1")).thenReturn(List.of(invoice.getBookingId(), "B9"));
        TestInvoiceLines.into(paymentReceiptService, invoiceRepository, lines, null);
        PaymentReceipt onAccount = PaymentReceipt.builder().id("D1").clientId("K1").isAdvance(true).direction(ReceiptDirection.RECEIPT)
                .amountInr(new BigDecimal("8850.00")).build();
        PaymentReceipt heldForB9 = PaymentReceipt.builder().id("D2").clientId("K1").bookingId("B9").isAdvance(true)
                .direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("1320.00")).build();
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1"))
                .thenReturn(List.of(onAccount, heldForB9));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());
        when(bookingAccountingSync.positionOn(invoice, invoice.getBookingId(), null))
                .thenReturn(new BookingAccountingSync.Position(new BigDecimal("15000.00"), BigDecimal.ZERO));

        assertThat(paymentReceiptService.availableWalletFor("K1", List.of(invoice.getBookingId(), "B9"))).isEqualByComparingTo("10170.00");
        paymentReceiptService.applyAvailableWallet(invoice);

        ArgumentCaptor<PaymentReceipt> saved = ArgumentCaptor.forClass(PaymentReceipt.class);
        org.mockito.Mockito.verify(paymentReceiptRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues()).filteredOn(r -> Boolean.TRUE.equals(r.getAppliedFromAdvance()))
                .extracting(PaymentReceipt::getBookingId, r -> r.getAmountInr().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("B9", "1320.00"),
                        org.assertj.core.groups.Tuple.tuple(invoice.getBookingId(), "8850.00"));
    }

    /** Money on account spread over a combined invoice goes to each booking up to what it owes, not all to the first. */
    @Test
    void walletOnAccountIsDrawnPerBookingUpToWhatEachOwes() {
        Invoice invoice = issuedInvoice(new BigDecimal("33540.00"));
        com.voyra.crm.repository.InvoiceLineItemRepository lines = org.mockito.Mockito.mock(com.voyra.crm.repository.InvoiceLineItemRepository.class);
        when(lines.findBookingIdsByInvoiceId("I1")).thenReturn(List.of(invoice.getBookingId(), "B9"));
        TestInvoiceLines.into(paymentReceiptService, invoiceRepository, lines, null);
        PaymentReceipt onAccount = PaymentReceipt.builder().id("D1").clientId("K1").isAdvance(true).direction(ReceiptDirection.RECEIPT)
                .amountInr(new BigDecimal("30000.00")).build();
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(onAccount));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());
        when(bookingAccountingSync.positionOn(invoice, invoice.getBookingId(), null))
                .thenReturn(new BookingAccountingSync.Position(new BigDecimal("22360.00"), BigDecimal.ZERO));

        paymentReceiptService.applyAvailableWallet(invoice);

        ArgumentCaptor<PaymentReceipt> saved = ArgumentCaptor.forClass(PaymentReceipt.class);
        org.mockito.Mockito.verify(paymentReceiptRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues()).filteredOn(r -> Boolean.TRUE.equals(r.getAppliedFromAdvance()))
                .extracting(PaymentReceipt::getBookingId, r -> r.getAmountInr().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(invoice.getBookingId(), "22360.00"),
                        org.assertj.core.groups.Tuple.tuple("B9", "7640.00"));
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
        // BUG-MO-09: the 2110 line names the client too, so a per-client 2110 balance nets the applied advance.
        assertThat(posting.lines().get(0).partyType()).isEqualTo("CLIENT");
        assertThat(posting.lines().get(0).partyId()).isEqualTo("K1");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1200"); // ACCOUNTS_RECEIVABLE
        assertThat(posting.lines().get(1).partyType()).isEqualTo("CLIENT");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("K1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void recordingAGatewayReceiptSplitsTheDebitBetweenNetDepositAndTheFeeWhileCreditingTheGrossAmount() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        PaymentReceiptRequest req = request(new BigDecimal("1000.00"));
        req.setGatewayProvider("Razorpay");
        req.setGatewayTxnRef("pay_P8qN2xK3Jd");
        req.setGatewayFee(new BigDecimal("28.00"));

        PaymentReceiptResponse response = paymentReceiptService.record(req);

        assertThat(response.getGatewayFeeInr()).isEqualByComparingTo("28.00");
        assertThat(response.getNetDepositInr()).isEqualByComparingTo("972.00");
        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("1000.00"); // gross, unreduced - Rule 5.3

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.GATEWAY_RECEIPT);
        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1110"); // BANK_ACCOUNTS, net
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("972.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("5610"); // PAYMENT_PROCESSING_FEES
        assertThat(posting.lines().get(1).debitAmount()).isEqualByComparingTo("28.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("1200"); // ACCOUNTS_RECEIVABLE, gross
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("1000.00");

        BigDecimal totalDebit = posting.lines().stream().map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = posting.lines().stream().map(l -> l.creditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    @Test
    void recordingAReceiptWithNoGatewayFeeKeepsTheOriginalTwoLineShape() {
        Invoice invoice = issuedInvoice(new BigDecimal("1000.00"));
        when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1"))
                .thenReturn(List.of(PaymentReceipt.builder().id("R1").invoiceId("I1")
                        .direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        PaymentReceiptResponse response = paymentReceiptService.record(request(new BigDecimal("1000.00")));

        assertThat(response.getGatewayFeeInr()).isNull();
        assertThat(response.getNetDepositInr()).isNull();
        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        assertThat(captor.getValue().lines()).hasSize(2);
        assertThat(captor.getValue().purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.RECEIPT_AGAINST_INVOICE);
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

    /** Regression run 6, M2: an advance taken for one booking is never drawn by another booking of the same client. */
    @Test
    void anAdvanceHeldForOneBookingIsReservedFromTheClientsOtherBookings() {
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("D1").bookingId("BA").isAdvance(true).amountInr(new BigDecimal("5000.00")).build(),
                PaymentReceipt.builder().id("D2").isAdvance(true).amountInr(new BigDecimal("1000.00")).build()));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());

        assertThat(paymentReceiptService.availableWalletFor("K1", "BA")).isEqualByComparingTo("6000.00");
        assertThat(paymentReceiptService.availableWalletFor("K1", "BB")).isEqualByComparingTo("1000.00");
    }

    @Test
    void onceItsOwnInvoiceHasDrawnTheAdvanceTheReservationIsReleased() {
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("D1").bookingId("BA").isAdvance(true).amountInr(new BigDecimal("5000.00")).build()));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("A1").bookingId("BA").appliedFromAdvance(true).amountInr(new BigDecimal("4000.00")).build()));

        assertThat(paymentReceiptService.availableWalletFor("K1", "BB")).isEqualByComparingTo("0.00");
        assertThat(paymentReceiptService.availableWalletFor("K1", "BA")).isEqualByComparingTo("1000.00");
    }

    /** Regression run 7, N1: refunding a held advance on cancel releases it rather than growing the pool. */
    @Test
    void aRefundPaidOutOfAHeldAdvanceDrawsItDown() {
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("D1").bookingId("BA").isAdvance(true).direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("2000.00")).build(),
                PaymentReceipt.builder().id("R1").bookingId("BA").isAdvance(true).direction(ReceiptDirection.REFUND).amountInr(new BigDecimal("1500.00")).build()));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of());

        assertThat(paymentReceiptService.heldForBooking("K1", "BA")).isEqualByComparingTo("500.00");
        assertThat(paymentReceiptService.availableWalletFor("K1", "BA")).isEqualByComparingTo("500.00");
    }

    /**
     * Round 2, NB-3 (the real BK-01006 / BK-01094 / BK-01244 rows): BK-01006's 5840 advance drew 1416
     * for its own invoice, then 4424 of it paid a BK-01094 invoice. The pool is
     * 5840 + 1920 - 1416 - 4424 = 1920, all of it BK-01244's advance; BK-01006 holds nothing any more.
     */
    @Test
    void anAdvanceAppliedToAnotherBookingsInvoiceStopsBeingHeldForItsOwn() {
        when(paymentReceiptRepository.findByClientIdAndInvoiceIdIsNullAndIsAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("D1").bookingId("B1006").isAdvance(true).amountInr(new BigDecimal("5840.00")).build(),
                PaymentReceipt.builder().id("D2").bookingId("B1244").isAdvance(true).amountInr(new BigDecimal("1920.00")).build()));
        when(paymentReceiptRepository.findByClientIdAndAppliedFromAdvanceTrueOrderByReceivedOnAsc("K1")).thenReturn(List.of(
                PaymentReceipt.builder().id("A1").bookingId("B1006").appliedFromAdvance(true).amountInr(new BigDecimal("1416.00")).build(),
                PaymentReceipt.builder().id("A2").bookingId("B1094").appliedFromAdvance(true).amountInr(new BigDecimal("4424.00")).build()));

        assertThat(paymentReceiptService.heldForBooking("K1", "B1006")).isEqualByComparingTo("0.00");
        assertThat(paymentReceiptService.heldForBooking("K1", "B1244")).isEqualByComparingTo("1920.00");
        assertThat(paymentReceiptService.availableWalletFor("K1", "B1244")).isEqualByComparingTo("1920.00");
        assertThat(paymentReceiptService.availableWalletFor("K1", "B1094")).isEqualByComparingTo("0.00");
    }
}
