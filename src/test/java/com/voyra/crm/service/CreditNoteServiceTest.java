package com.voyra.crm.service;

import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditNoteServiceTest {

    @Mock
    private CreditNoteRepository creditNoteRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private com.voyra.crm.repository.BookingRepository bookingRepository;
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
    private com.voyra.crm.repository.JournalLineRepository journalLineRepository;

    @InjectMocks
    private CreditNoteService creditNoteService;

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

    /** taxableValue 1000, cgst 90, sgst 90 (18% intra-state), grandTotal 1180. */
    private Invoice issuedInvoice() {
        return Invoice.builder().id("I1").invoiceNumber("INV/2026-27/0001")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .clientId("K1").clientName("Rahul Verma").bookingId("B1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .fxRateSource(FxRateSource.INR_IDENTITY)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("90.00"))
                .sgstAmount(new BigDecimal("90.00")).igstAmount(BigDecimal.ZERO).tcsAmount(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("1180.00")).grandTotalInr(new BigDecimal("1180.00"))
                .amountReceived(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(new BigDecimal("1180.00")).balanceDueInr(new BigDecimal("1180.00")).build();
    }

    private CreditNoteRequest request(BigDecimal cancellationFee) {
        CreditNoteRequest r = new CreditNoteRequest();
        r.setInvoiceId("I1");
        r.setReason(CreditNoteReason.BOOKING_CANCELLED);
        r.setReasonNote("Client cancelled");
        r.setCancellationFee(cancellationFee);
        return r;
    }

    @Test
    void draftingAgainstADraftInvoiceIsRejected() {
        Invoice draft = Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> creditNoteService.create(request(BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an issued invoice can be credited");
    }

    @Test
    void bookingCancelledReasonIsRejectedWhenTheBookingIsNotActuallyCancelled() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Booking.builder().id("B1").bookingStatus(com.voyra.crm.enums.BookingStatus.CONFIRMED).build()));

        assertThatThrownBy(() -> creditNoteService.create(request(BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the booking to actually be cancelled");
    }

    @Test
    void billingErrorReasonWorksOnALiveBooking() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        CreditNoteRequest request = request(BigDecimal.ZERO);
        request.setReason(CreditNoteReason.BILLING_ERROR);

        CreditNoteResponse response = creditNoteService.create(request);

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.DRAFT);
        org.mockito.Mockito.verifyNoInteractions(bookingRepository);
    }

    @Test
    void aFullCreditReversesTheEntireTaxableValueAndTax() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        CreditNoteResponse response = creditNoteService.create(request(BigDecimal.ZERO));

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.DRAFT);
        assertThat(response.getTaxableValue()).isEqualByComparingTo("1000.00");
        assertThat(response.getCgstAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1180.00");
    }

    @Test
    void aPartialCreditWithARetentionFeeReversesOnlyItsProRataShare() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.existsById(any())).thenReturn(false);
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());

        // Retain 200 of the 1000 taxable value -> 80% credited.
        CreditNoteResponse response = creditNoteService.create(request(new BigDecimal("200.00")));

        assertThat(response.getTaxableValue()).isEqualByComparingTo("800.00");
        assertThat(response.getCgstAmount()).isEqualByComparingTo("72.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("72.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("944.00");
    }

    @Test
    void cancellationFeeAboveTaxableValueIsRejected() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> creditNoteService.create(request(new BigDecimal("5000.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void issuingAllocatesANumberAndReducesTheInvoiceBalance() {
        Invoice invoice = issuedInvoice();
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("90.00"))
                .sgstAmount(new BigDecimal("90.00")).totalAmount(new BigDecimal("1180.00"))
                .totalAmountInr(new BigDecimal("1180.00")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1180.00")).build()));

        CreditNoteResponse response = creditNoteService.issue("CN1");

        assertThat(response.getCreditNoteNumber()).isEqualTo("CN/2026-27/0001");
        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.ISSUED);
        assertThat(response.getRefundableAmount()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getCreditNoteTotal()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
    }

    @Test
    void issuingPostsAJournalReversingTheOriginalRevenueAccount() {
        Invoice invoice = issuedInvoice();
        invoice.setServiceCategory(com.voyra.crm.enums.InvoiceServiceCategory.PACKAGE);
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("90.00"))
                .sgstAmount(new BigDecimal("90.00")).totalAmount(new BigDecimal("1180.00"))
                .totalAmountInr(new BigDecimal("1180.00")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0001");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1180.00")).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, "I1", com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, "I1", com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED))
                .thenReturn(Optional.of(com.voyra.crm.entity.JournalEntry.builder().id("JE1").build()));

        creditNoteService.issue("CN1");

        org.mockito.ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                org.mockito.ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.CREDIT_NOTE_ISSUED);
        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("4070"); // PACKAGE is index 6 -> (6+1)*10
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("1000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("2310");
        assertThat(posting.lines().get(1).debitAmount()).isEqualByComparingTo("180.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("1200");
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("1180.00");
    }

    /** Regression run 6, B1: a commission-agent invoice has no single revenue account, so its
     *  credit note used to post no journal at all and left AR and fee income overstated. */
    @Test
    void creditNoteOnACommissionAgentInvoiceReversesItsOwnLinesAgainstReceivables() {
        Invoice invoice = issuedInvoice();
        invoice.setGrandTotal(new BigDecimal("8000.00"));
        invoice.setGrandTotalInr(new BigDecimal("8000.00"));
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("3389.83")).totalAmount(new BigDecimal("4000.00"))
                .totalAmountInr(new BigDecimal("4000.00")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0003");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, "I1", com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, "I1", com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.INVOICE, "I1", com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.of(com.voyra.crm.entity.JournalEntry.builder().id("JE9").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE9")).thenReturn(List.of(
                com.voyra.crm.entity.JournalLine.builder().accountCode("1200").debitAmountInr(new BigDecimal("8000.00")).creditAmountInr(BigDecimal.ZERO).narration("AR").build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("2400").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("7000.00")).narration("Pass-through").build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("4600").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("847.46")).narration("Fee").build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("2310").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("152.54")).narration("GST").build()));

        creditNoteService.issue("CN1");

        org.mockito.ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                org.mockito.ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        var lines = captor.getValue().lines();
        assertThat(lines).extracting(com.voyra.crm.models.JournalLinePosting::accountCode).containsExactly("2400", "4600", "2310", "1200");
        assertThat(lines.get(0).debitAmount()).isEqualByComparingTo("3500.00");
        assertThat(lines.get(1).debitAmount()).isEqualByComparingTo("423.73");
        assertThat(lines.get(2).debitAmount()).isEqualByComparingTo("76.27");
        assertThat(lines.get(3).creditAmount()).isEqualByComparingTo("4000.00");
    }

    /** Reproduces the audit finding (F-016): a ₹9,975 invoice with only ₹5,000 ever actually
     *  receipted must not become refundable for its full ₹9,975 face value once credited. */
    @Test
    void issuingCapsRefundableAmountAtCashActuallyReceiptedOnTheInvoice() {
        Invoice invoice = issuedInvoice();
        invoice.setGrandTotal(new BigDecimal("9975.00"));
        invoice.setAmountReceived(new BigDecimal("5000.00"));
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("9975.00")).totalAmountInr(new BigDecimal("9975.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0002");
        // One advance receipt before conversion - exactly the audit's repro.
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("5000.00")).build()));

        CreditNoteResponse response = creditNoteService.issue("CN1");

        assertThat(response.getRefundableAmount()).isEqualByComparingTo("5000.00");
    }

    /** A partly paid invoice partly credited: only cash beyond what is still billed comes back.
     *  1180 billed, 1000 paid, 590 credited -> 590 still billed -> 410 refundable, not 590. */
    @Test
    void partialCreditOnAPartlyPaidInvoiceRefundsOnlyTheOverpayment() {
        Invoice invoice = issuedInvoice();
        invoice.setAmountReceived(new BigDecimal("1000.00"));
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("590.00")).totalAmountInr(new BigDecimal("590.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.CREDIT_NOTE, LocalDate.now())).thenReturn("CN/2026-27/0003");
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1000.00")).build()));

        CreditNoteResponse response = creditNoteService.issue("CN1");

        assertThat(response.getRefundableAmount()).isEqualByComparingTo("410.00");
    }

    @Test
    void issuingBeyondTheInvoicesGrandTotalIsRejected() {
        Invoice invoice = issuedInvoice();
        CreditNote draft = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.DRAFT).totalAmount(new BigDecimal("1180.00")).build();
        CreditNote alreadyIssued = CreditNote.builder().id("CN0").invoiceId("I1")
                .status(CreditNoteStatus.ISSUED).totalAmount(new BigDecimal("500.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of(alreadyIssued));

        assertThatThrownBy(() -> creditNoteService.issue("CN1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than the invoice's grand total");
    }

    @Test
    void cancellingAnIssuedCreditNoteWithNoRefundsReversesTheLedgerAndInvoiceBalance() {
        Invoice invoice = issuedInvoice();
        invoice.setCreditNoteTotal(new BigDecimal("1180.00"));
        invoice.setBalanceDue(BigDecimal.ZERO);
        invoice.setBalanceDueInr(BigDecimal.ZERO);
        invoice.setStatus(InvoiceLifecycle.PAID);
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .creditNoteNumber("CN/2026-27/0001").status(CreditNoteStatus.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).totalAmountInr(new BigDecimal("1180.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));

        CreditNoteResponse response = creditNoteService.cancel("CN1", "Raised in error");

        assertThat(response.getStatus()).isEqualTo(CreditNoteStatus.CANCELLED);
        assertThat(invoice.getCreditNoteTotal()).isEqualByComparingTo("0.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("1180.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
    }

    @Test
    void cancellingACreditNoteWithRefundsAgainstItIsRejected() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").status(CreditNoteStatus.ISSUED)
                .totalAmount(new BigDecimal("1180.00")).refundedAmount(new BigDecimal("500.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));

        assertThatThrownBy(() -> creditNoteService.cancel("CN1", "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refunds against it");
    }

    @Test
    void refundingWritesAReceiptAndAccumulatesRefundedAmount() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .creditNoteNumber("CN/2026-27/0001").status(CreditNoteStatus.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).refundableAmount(new BigDecimal("1180.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1")).thenReturn(List.of());
        when(paymentReceiptRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 9, 19))).thenReturn("RCP/2026-27/0010");

        CreditNoteRefundRequest request = new CreditNoteRefundRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(PaymentMode.BANK_TRANSFER);
        request.setReceivedOn(LocalDate.of(2026, 9, 19));

        CreditNoteResponse response = creditNoteService.refund("CN1", request);

        assertThat(response.getRefundedAmount()).isEqualByComparingTo("500.00");
        org.mockito.ArgumentCaptor<PaymentReceipt> captor = org.mockito.ArgumentCaptor.forClass(PaymentReceipt.class);
        org.mockito.Mockito.verify(paymentReceiptRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(ReceiptDirection.REFUND);
        assertThat(captor.getValue().getCreditNoteId()).isEqualTo("CN1");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void refundingMoreThanTheRemainingBalanceIsRejected() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .status(CreditNoteStatus.ISSUED).refundableAmount(new BigDecimal("1180.00"))
                .refundedAmount(new BigDecimal("1000.00")).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1"))
                .thenReturn(List.of(PaymentReceipt.builder().amount(new BigDecimal("1000.00")).build()));

        CreditNoteRefundRequest request = new CreditNoteRefundRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(PaymentMode.CASH);
        request.setReceivedOn(LocalDate.of(2026, 9, 19));

        assertThatThrownBy(() -> creditNoteService.refund("CN1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the remaining refundable balance");
    }

    @Test
    void refundingBringsTheInvoiceBalanceBackToZeroInsteadOfLeavingItNegative() {
        // 1,180 fully paid, then fully credited: balance is -1,180 until the cash goes back.
        Invoice invoice = issuedInvoice();
        invoice.setAmountReceived(new BigDecimal("1180.00"));
        invoice.setCreditNoteTotal(new BigDecimal("1180.00"));
        invoice.setBalanceDue(new BigDecimal("-1180.00"));
        invoice.setBalanceDueInr(new BigDecimal("-1180.00"));
        CreditNote note = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1").creditNoteNumber("CN/1")
                .status(CreditNoteStatus.ISSUED).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).totalAmountInr(new BigDecimal("1180.00"))
                .refundableAmount(new BigDecimal("1180.00")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(note));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1")).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 10, 1))).thenReturn("RCP/9");

        CreditNoteRefundRequest req = new CreditNoteRefundRequest();
        req.setAmount(new BigDecimal("1180.00"));
        req.setPaymentMode(com.voyra.crm.enums.PaymentMode.BANK_TRANSFER);
        req.setReceivedOn(LocalDate.of(2026, 10, 1));
        creditNoteService.refund("CN1", req);

        assertThat(invoice.getAmountReceived()).isEqualByComparingTo("0.00");
        assertThat(invoice.getBalanceDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void refundToWalletCreditsClientAdvancesInsteadOfTheBank() {
        CreditNote issued = CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1")
                .creditNoteNumber("CN/2026-27/0001").status(CreditNoteStatus.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1180.00")).refundableAmount(new BigDecimal("1180.00"))
                .refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(paymentReceiptRepository.findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc("CN1")).thenReturn(List.of());
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 10, 16))).thenReturn("RCP/11");

        CreditNoteRefundRequest request = new CreditNoteRefundRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(PaymentMode.ADJUSTMENT);
        request.setReceivedOn(LocalDate.of(2026, 10, 16));
        creditNoteService.refund("CN1", request, true);

        org.mockito.ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor =
                org.mockito.ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        assertThat(captor.getValue().lines()).extracting(com.voyra.crm.models.JournalLinePosting::accountCode)
                .containsExactlyInAnyOrder(com.voyra.crm.enums.SystemAccount.ACCOUNTS_RECEIVABLE.code(),
                        com.voyra.crm.enums.SystemAccount.CLIENT_ADVANCES_HELD.code());
    }
}
