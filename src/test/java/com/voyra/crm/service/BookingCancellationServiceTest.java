package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.RefundSettlementRequest;
import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.SupplierCreditNoteRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingCancellationServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingAccountingSync bookingAccountingSync;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private CreditNoteService creditNoteService;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @Mock private SupplierCreditNoteRepository supplierCreditNoteRepository;
    @Mock private SupplierCreditNoteService supplierCreditNoteService;
    @Mock private PaymentReceiptService paymentReceiptService;
    @Mock private RevenueRecognitionTenantService revenueRecognitionTenantService;
    @Mock private SupplierPaymentService supplierPaymentService;
    @Mock private AuthorResolver authorResolver;
    @Mock private BookingInvoiceIssuer bookingInvoiceIssuer;

    /** GST-free by default: the charges invoice bills exactly what was kept (tests that need GST stub it). */
    @Mock private InvoiceDocumentService invoiceDocumentService;

    /** GST-free by default: the charges invoice bills exactly what was kept (tests that need GST stub it). */
    @org.junit.jupiter.api.BeforeEach
    void chargesInvoiceBillsWhatWasKept() {
        org.mockito.Mockito.lenient().when(bookingInvoiceIssuer.cancellationChargesTotal(any(), any(), any()))
                .thenAnswer(i -> ((BigDecimal) i.getArgument(1)).add(i.getArgument(2)));
        // Each invoice here bills only this booking: its share is the whole invoice.
        org.mockito.Mockito.lenient().when(creditNoteService.shareOf(any(), any()))
                .thenAnswer(i -> new CreditNoteService.Share(((Invoice) i.getArgument(0)).getTaxableValue(), BigDecimal.ONE));
    }

    @InjectMocks private BookingCancellationService service;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Booking booking(BookingStatus status) {
        Booking b = Booking.builder().id("B1").clientId("C1").bookingStatus(status)
                .netCost(d("8000")).sellingPrice(d("10000"))
                .receivedTotalInr(d("10000")).refundedTotalInr(BigDecimal.ZERO).build();
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        return b;
    }

    private static BookingCancelRequest cancel(String charge, String fee) {
        BookingCancelRequest r = new BookingCancelRequest();
        r.setType(CancellationType.CANCELLATION);
        r.setReason("Client changed plans");
        r.setSupplierCharge(d(charge));
        r.setAgencyFee(d(fee));
        return r;
    }

    private static RefundSettlementRequest pay(String amount) {
        RefundSettlementRequest r = new RefundSettlementRequest();
        r.setAmount(d(amount));
        r.setPaidOn(LocalDate.of(2026, 10, 16));
        r.setPaymentMode(PaymentMode.UPI);
        r.setReference("UTR1");
        return r;
    }

    /** Regression run 8, R8-1: refunds and the cancel itself lock the booking before reading what is owed. */
    @Test
    void refundsAndCancelTakeTheBookingLockFirst() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("4000"));
        b.setSupplierRefundExpected(d("3000"));

        service.payCustomer("B1", pay("4000"));
        // The fixture has no supplier credit note and refunds are still open, so these two are refused - after the lock.
        assertThatThrownBy(() -> service.recordSupplierRefund("B1", pay("3000"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.markChecked("B1")).isInstanceOf(IllegalStateException.class);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, bookingService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        order.verify(bookingService).findAccessibleBooking("B1");
        verify(bookingRepository, org.mockito.Mockito.times(3)).findByIdForUpdate("B1");
    }

    @Test
    void theAirlineChargeAndTheSupplierFeeBothComeOffTheRefundAndTheAirlinePartIsKept() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(BigDecimal.ZERO);
        when(paymentReceiptService.heldForBooking("C1", "B1")).thenReturn(d("10000"));

        assertThat(service.preview("B1", d("3000"), d("500"), d("1000")).getCustomerRefundDue()).isEqualByComparingTo("5500");
        BookingCancelRequest request = cancel("500", "1000");
        request.setAirlineCharge(d("3000"));
        service.cancel("B1", request);

        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("5500");
        assertThat(b.getCancellationSupplierCharge()).isEqualByComparingTo("3500");
        assertThat(b.getCancellationAirlineCharge()).isEqualByComparingTo("3000");
        assertThat(b.getCancellationAgencyFee()).isEqualByComparingTo("1000");
    }

    /** Cancelled before any invoice: the kept charges get their own invoice (advance applied to it) and the refund is what is left. */
    @Test
    void keptChargesBeforeAnyInvoiceAreBilledOnTheirOwnInvoice() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(BigDecimal.ZERO);
        when(paymentReceiptService.heldForBooking("C1", "B1")).thenReturn(d("5000"));
        when(bookingInvoiceIssuer.issueCancellationCharges(b, d("1200"), d("300")))
                .thenReturn(com.voyra.crm.dto.InvoiceResponse.builder().id("CI1").grandTotal(d("1554")).build());
        when(bookingInvoiceIssuer.cancellationChargesTotal(b, d("1200"), d("300"))).thenReturn(d("1554"));
        com.voyra.crm.entity.Invoice chargesInvoice = com.voyra.crm.entity.Invoice.builder().id("CI1").build();
        when(invoiceRepository.findById("CI1")).thenReturn(java.util.Optional.of(chargesInvoice));
        BookingCancelRequest r = cancel("200", "300");
        r.setAirlineCharge(d("1000"));

        // The preview shows the refund the cancel then gives, GST on the charges included.
        assertThat(service.preview("B1", d("1000"), d("200"), d("300")).getCustomerRefundDue()).isEqualByComparingTo("3446");
        service.cancel("B1", r);

        // Issued while the booking is still live, then the booking is cancelled.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingInvoiceIssuer, bookingService);
        order.verify(bookingInvoiceIssuer).issueCancellationCharges(b, d("1200"), d("300"));
        order.verify(bookingService).markCancelled(org.mockito.ArgumentMatchers.eq(b), any());
        // 5,000 paid less the 1,554 charges invoice (GST included).
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("3446");
        verify(creditNoteService, never()).create(any());
        // The trip won't happen, so what was kept is earned now, not deferred to a departure date.
        verify(revenueRecognitionTenantService).recognizeRetainedOnCancellation(chargesInvoice, BigDecimal.ZERO, LocalDate.now());
    }

    /** Regression run 7, N1: an advance held on account for the booking (no invoice yet) is refundable on cancel. */
    @Test
    void anAdvanceHeldForTheBookingIsRefundableWhenItIsCancelledBeforeInvoicing() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(BigDecimal.ZERO);
        when(paymentReceiptService.heldForBooking("C1", "B1")).thenReturn(d("2000"));

        assertThat(service.preview("B1", null, d("500"), BigDecimal.ZERO).getCustomerRefundDue()).isEqualByComparingTo("1500");
        service.cancel("B1", cancel("500", "0"));

        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("1500");
        assertThat(b.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
    }

    @Test
    void withoutInvoiceOrBillTheRefundIsPaidLessWhatIsKept() {
        Booking b = booking(BookingStatus.CONFIRMED);

        service.cancel("B1", cancel("2000", "500"));

        verify(bookingService).markCancelled(b, "Client changed plans");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("7500");
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("0");
        assertThat(b.getCancellationSupplierCharge()).isEqualByComparingTo("2000");
        assertThat(b.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
        verify(creditNoteService, never()).create(any());
    }

    @Test
    void customerWhoPaidLessThanTheChargesIsOwedNothing() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(d("1000"));

        service.cancel("B1", cancel("2000", "0"));

        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("0");
        assertThat(b.getRefundState()).isEqualTo(RefundState.NOT_APPLICABLE);
    }

    @Test
    void invoicedBookingGetsACreditNoteAndKeptRevenueIsRecognised() {
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.PAID).taxableValue(d("9000")).creditNoteTotal(BigDecimal.ZERO).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        when(creditNoteService.create(any())).thenReturn(CreditNoteResponse.builder().id("CN1").build());
        when(creditNoteService.issue("CN1")).thenReturn(CreditNoteResponse.builder().id("CN1")
                .refundableAmount(d("7200")).taxableValue(d("6500")).build());

        service.cancel("B1", cancel("2000", "500"));

        ArgumentCaptor<CreditNoteRequest> note = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService).create(note.capture());
        assertThat(note.getValue().getCancellationFee()).isEqualByComparingTo("2500");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("7200");
        verify(revenueRecognitionTenantService).recognizeRetainedOnCancellation(eq(invoice), eq(d("6500")), any());
    }

    // ---- BUG-MO-01: a booking with an extra invoice gets a credit note on every invoice ----------

    private static Invoice taxInvoice(String id, String taxable) {
        return Invoice.builder().id(id).bookingId("B1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.PAID).taxableValue(d(taxable)).creditNoteTotal(BigDecimal.ZERO).build();
    }

    /** Stubs the two invoices and makes create/issue answer per invoice with the given refundable amounts. */
    private void twoInvoices(Invoice first, Invoice extra, String firstRefundable, String extraRefundable) {
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(first, extra));
        when(creditNoteService.create(any())).thenAnswer(i -> CreditNoteResponse.builder()
                .id("CN-" + ((CreditNoteRequest) i.getArgument(0)).getInvoiceId()).build());
        when(creditNoteService.issue("CN-" + first.getId())).thenReturn(CreditNoteResponse.builder()
                .refundableAmount(d(firstRefundable)).taxableValue(BigDecimal.ZERO).build());
        when(creditNoteService.issue("CN-" + extra.getId())).thenReturn(CreditNoteResponse.builder()
                .refundableAmount(d(extraRefundable)).taxableValue(BigDecimal.ZERO).build());
    }

    /**
     * BK-00998 (flight, commission agent): invoice 1 = fare 10,000 + fee 2,000 + 18% GST on the fee 360 = 12,360;
     * extra invoice = fare 2,000 + fee 500 + GST 90 = 2,590. Paid 14,950. Full refund, nothing kept:
     * both invoices are credited in full and the refund is 12,360 + 2,590 = 14,950 (was 12,360).
     */
    @Test
    void fullRefundCreditsEveryInvoiceOnTheBooking() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(d("14950"));
        Invoice first = taxInvoice("I1", "12000");
        Invoice extra = taxInvoice("I2", "2500");
        twoInvoices(first, extra, "12360", "2590");
        when(creditNoteService.previewRefundable(first, "B1", d("0"), d("0"))).thenReturn(d("12360"));
        when(creditNoteService.previewRefundable(extra, "B1", d("0"), d("0"))).thenReturn(d("2590"));
        BookingCancelRequest r = cancel("0", "0");
        r.setType(CancellationType.FULL_REFUND);

        assertThat(service.preview("B1", null, d("0"), d("0")).getCustomerRefundDue()).isEqualByComparingTo("14950");
        service.cancel("B1", r);

        ArgumentCaptor<CreditNoteRequest> notes = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService, org.mockito.Mockito.times(2)).create(notes.capture());
        assertThat(notes.getAllValues()).extracting(CreditNoteRequest::getInvoiceId).containsExactly("I1", "I2");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("14950");
        verify(revenueRecognitionTenantService).recognizeRetainedOnCancellation(eq(first), any(), any());
        verify(revenueRecognitionTenantService).recognizeRetainedOnCancellation(eq(extra), any(), any());
    }

    /**
     * Same flight with an airline charge 1,000 and an agency fee 500: what is kept comes off the first
     * invoice (supplier retained 1,000, cancellation fee 1,500); the extra invoice is credited in full.
     * Invoice 1 credit: taxable 12,000 - 1,500 = 10,500; fare back 10,000 - 1,000 = 9,000, fee back 1,500,
     * GST back 360 x 1,500/2,000 = 270 -> 10,770. Extra: 2,590. Refund 13,360 = 14,950 - 1,500 kept - 90 GST on the kept fee.
     */
    @Test
    void chargesComeOffTheFirstInvoiceAndTheExtraInvoiceIsCreditedInFull() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(d("14950"));
        Invoice first = taxInvoice("I1", "12000");
        Invoice extra = taxInvoice("I2", "2500");
        twoInvoices(first, extra, "10770", "2590");
        when(creditNoteService.previewRefundable(first, "B1", d("1500"), d("1000"))).thenReturn(d("10770"));
        when(creditNoteService.previewRefundable(extra, "B1", d("0"), d("0"))).thenReturn(d("2590"));
        BookingCancelRequest r = cancel("0", "500");
        r.setAirlineCharge(d("1000"));

        assertThat(service.preview("B1", d("1000"), d("0"), d("500")).getCustomerRefundDue()).isEqualByComparingTo("13360");
        service.cancel("B1", r);

        ArgumentCaptor<CreditNoteRequest> notes = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService, org.mockito.Mockito.times(2)).create(notes.capture());
        assertThat(notes.getAllValues().get(0).getCancellationFee()).isEqualByComparingTo("1500");
        assertThat(notes.getAllValues().get(0).getSupplierRetained()).isEqualByComparingTo("1000");
        assertThat(notes.getAllValues().get(1).getCancellationFee()).isEqualByComparingTo("0");
        assertThat(notes.getAllValues().get(1).getSupplierRetained()).isEqualByComparingTo("0");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("13360");
    }

    /**
     * Hotel (principal, GST on the whole price): invoice 1 = 12,000 + 2,160 = 14,160; extra = 2,500 + 450 = 2,950;
     * paid 17,110. Hotel keeps 12,500, agency fee 1,000 - more than invoice 1 holds, so it spills into the
     * extra invoice: invoice 1 kept 12,000 (all of it, refund 0); extra kept 500 + 1,000 = 1,500, credit
     * 1,000 + 18% = 1,180. Check: 17,110 - 13,500 x 1.18 (15,930) = 1,180.
     */
    @Test
    void chargesAboveTheFirstInvoiceSpillIntoTheNext() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setType(com.voyra.crm.enums.BookingType.HOTEL);
        b.setReceivedTotalInr(d("17110"));
        Invoice first = taxInvoice("I1", "12000");
        Invoice extra = taxInvoice("I2", "2500");
        twoInvoices(first, extra, "0", "1180");
        when(creditNoteService.previewRefundable(first, "B1", d("12000"), d("12000"))).thenReturn(d("0"));
        when(creditNoteService.previewRefundable(extra, "B1", d("1500"), d("500"))).thenReturn(d("1180"));
        BookingCancelRequest r = cancel("0", "1000");
        r.setAirlineCharge(d("12500"));

        assertThat(service.preview("B1", d("12500"), d("0"), d("1000")).getCustomerRefundDue()).isEqualByComparingTo("1180");
        service.cancel("B1", r);

        ArgumentCaptor<CreditNoteRequest> notes = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService, org.mockito.Mockito.times(2)).create(notes.capture());
        assertThat(notes.getAllValues().get(1).getCancellationFee()).isEqualByComparingTo("1500");
        assertThat(notes.getAllValues().get(1).getSupplierRetained()).isEqualByComparingTo("500");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("1180");
    }

    /**
     * BUG-MO-16, BK-00999: 13,000 received before the invoice, the 12,360 invoice drew 12,360 from it,
     * 640 still held for the booking. Void with a 300 supplier fee: the credit note gives back 12,060 and
     * the 640 held goes back too - refund 12,700, customer paid 12,360 + 640 = 13,000.
     */
    @Test
    void moneyStillHeldForAnInvoicedBookingIsPartOfTheRefund() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setType(com.voyra.crm.enums.BookingType.FLIGHT);
        b.setReceivedTotalInr(d("12360"));
        when(paymentReceiptService.heldForBooking("C1", "B1")).thenReturn(d("640"));
        Invoice invoice = taxInvoice("I1", "12000");
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        when(creditNoteService.previewRefundable(invoice, "B1", d("300"), d("300"))).thenReturn(d("12060"));
        when(creditNoteService.create(any())).thenReturn(CreditNoteResponse.builder().id("CN1").build());
        when(creditNoteService.issue("CN1")).thenReturn(CreditNoteResponse.builder().refundableAmount(d("12060")).taxableValue(d("11700")).build());
        BookingCancelRequest r = cancel("300", "0");
        r.setType(CancellationType.VOID);

        var preview = service.preview("B1", null, d("300"), d("0"));
        assertThat(preview.getCustomerPaid()).isEqualByComparingTo("13000");
        assertThat(preview.getCustomerRefundDue()).isEqualByComparingTo("12700");
        service.cancel("B1", r);

        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("12700");
    }

    /** The refund is paid out of each credit note in turn, and what is left of it out of the advance still held. */
    @Test
    void aRefundIsPaidAcrossTheCreditNotesThenFromTheHeldAdvance() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("12700"));
        com.voyra.crm.entity.CreditNote cn1 = com.voyra.crm.entity.CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED)
                .refundableAmount(d("12060")).refundedAmount(d("1000")).build();
        com.voyra.crm.entity.CreditNote cn2 = com.voyra.crm.entity.CreditNote.builder().id("CN2").status(CreditNoteStatus.ISSUED)
                .refundableAmount(d("0")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(cn1, cn2));

        // 11,700 asked: 11,060 still open on CN1, nothing on CN2, the last 640 from the advance.
        service.payCustomer("B1", pay("11700"));

        ArgumentCaptor<CreditNoteRefundRequest> refund = ArgumentCaptor.forClass(CreditNoteRefundRequest.class);
        verify(creditNoteService).refund(eq("CN1"), refund.capture(), eq(false));
        assertThat(refund.getValue().getAmount()).isEqualByComparingTo("11060");
        verify(creditNoteService, never()).refund(eq("CN2"), any(), org.mockito.ArgumentMatchers.anyBoolean());
        ArgumentCaptor<RefundSettlementRequest> rest = ArgumentCaptor.forClass(RefundSettlementRequest.class);
        verify(paymentReceiptService).refundAdvance(eq(b), any(), rest.capture(), eq(false));
        assertThat(rest.getValue().getAmount()).isEqualByComparingTo("640");
    }

    /** An invoice billed in AED at 25: the rupee charges are credited in AED and the AED refund comes back as rupees. */
    @Test
    void anInvoiceInAnotherCurrencyIsCreditedInItsCurrencyAndRefundedInRupees() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(d("22000"));
        Invoice invoice = taxInvoice("I1", "880");
        invoice.setCurrencyCode("AED");
        invoice.setFxRateToInr(d("25"));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        // 3,500 + 1,000 kept = AED 180 off the AED 880, the supplier's AED 140 of it; AED 700 comes back.
        when(creditNoteService.previewRefundable(invoice, "B1", d("180.00"), d("140.00"))).thenReturn(d("700"));
        when(creditNoteService.create(any())).thenReturn(CreditNoteResponse.builder().id("CN1").build());
        when(creditNoteService.issue("CN1")).thenReturn(CreditNoteResponse.builder().refundableAmount(d("700")).taxableValue(d("700")).build());

        assertThat(service.preview("B1", null, d("3500"), d("1000")).getCustomerRefundDue()).isEqualByComparingTo("17500");
        service.cancel("B1", cancel("3500", "1000"));

        ArgumentCaptor<CreditNoteRequest> note = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService).create(note.capture());
        assertThat(note.getValue().getCancellationFee()).isEqualByComparingTo("180");
        assertThat(note.getValue().getSupplierRetained()).isEqualByComparingTo("140");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("17500");
    }

    /** The refund is asked in rupees and taken off the AED note in AED - all of what is open when the rupees cover it. */
    @Test
    void aRefundOnAForeignCurrencyNoteIsTakenInThatCurrency() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("17500"));
        com.voyra.crm.entity.CreditNote cn = com.voyra.crm.entity.CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED)
                .fxRateToInr(d("25")).refundableAmount(d("700")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(cn));

        service.payCustomer("B1", pay("5000"));
        service.payCustomer("B1", pay("17500"));

        ArgumentCaptor<CreditNoteRefundRequest> refund = ArgumentCaptor.forClass(CreditNoteRefundRequest.class);
        verify(creditNoteService, org.mockito.Mockito.times(2)).refund(eq("CN1"), refund.capture(), eq(false));
        assertThat(refund.getAllValues().get(0).getAmount()).isEqualByComparingTo("200");
        assertThat(refund.getAllValues().get(1).getAmount()).isEqualByComparingTo("700");
        verify(paymentReceiptService, never()).refundAdvance(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** Another booking on the same invoice was cancelled first: this one still gets its own credit note, for its part. */
    @Test
    void theSecondBookingCancelledOnASharedInvoiceGetsItsOwnCreditNote() {
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice invoice = Invoice.builder().id("I1").bookingId("B9").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.PARTIALLY_PAID).taxableValue(d("20000")).grandTotal(d("20000")).creditNoteTotal(d("10000")).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", com.voyra.crm.enums.CreditNoteStatus.ISSUED)).thenReturn(List.of(
                com.voyra.crm.entity.CreditNote.builder().id("CN0").invoiceId("I1").bookingId("B9").build()));
        when(creditNoteService.shareOf(invoice, "B1")).thenReturn(new CreditNoteService.Share(d("10000"), new BigDecimal("0.5")));
        when(creditNoteService.create(any())).thenReturn(CreditNoteResponse.builder().id("CN1").build());
        when(creditNoteService.issue("CN1")).thenReturn(CreditNoteResponse.builder().refundableAmount(d("0")).taxableValue(d("9000")).build());

        service.cancel("B1", cancel("1000", "0"));

        ArgumentCaptor<CreditNoteRequest> note = ArgumentCaptor.forClass(CreditNoteRequest.class);
        verify(creditNoteService).create(note.capture());
        assertThat(note.getValue().getBookingId()).isEqualTo("B1");
        assertThat(note.getValue().getCancellationFee()).isEqualByComparingTo("1000");
        verify(bookingInvoiceIssuer, never()).issueCancellationCharges(any(), any(), any());
        // It was taken off any draft before anything else.
        verify(invoiceDocumentService).removeBookingFromDraft("B1");
    }

    @Test
    void invoiceAlreadyCreditedByHandGetsNoSecondCreditNote() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setReceivedTotalInr(d("10000"));
        b.setRefundedTotalInr(d("10000"));
        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.PAID).taxableValue(d("36000")).grandTotal(d("42480")).creditNoteTotal(d("42480")).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));

        assertThat(service.preview("B1", null, d("5000"), d("1000")).getCustomerRefundDue()).isEqualByComparingTo("0");
        service.cancel("B1", cancel("5000", "1000"));

        verify(creditNoteService, never()).create(any());
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("0");
    }

    @Test
    void draftBillIsCancelledAndApprovedBillGetsASupplierCreditNote() {
        Booking b = booking(BookingStatus.CONFIRMED);
        SupplierInvoice draft = SupplierInvoice.builder().id("S0").status(SupplierInvoiceStatus.DRAFT)
                .grandTotal(d("1000")).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO).build();
        SupplierInvoice paid = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.PAID)
                .grandTotal(d("8000")).amountPaid(d("8000")).creditNoteTotal(BigDecimal.ZERO)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(draft, paid));

        service.cancel("B1", cancel("2000", "0"));

        verify(supplierInvoiceService).cancelForBooking(eq("S0"), anyString());
        ArgumentCaptor<SupplierCreditNoteRequest> note = ArgumentCaptor.forClass(SupplierCreditNoteRequest.class);
        verify(supplierCreditNoteService).record(note.capture());
        assertThat(note.getValue().getTaxableValue()).isEqualByComparingTo("6000");
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("6000");
    }

    /** A booking whose supplier was never paid: the supplier's charge is owed, so the draft bill becomes a payable for it. */
    @Test
    void unpaidDraftBillBecomesAPayableForTheSuppliersCharge() {
        Booking b = booking(BookingStatus.CONFIRMED);
        SupplierInvoice draft = SupplierInvoice.builder().id("S0").status(SupplierInvoiceStatus.DRAFT)
                .grandTotal(d("24000")).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(draft));
        when(supplierInvoiceService.approveForBooking(eq("S0"), anyString())).thenAnswer(inv -> {
            draft.setStatus(SupplierInvoiceStatus.APPROVED);
            return com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S0").status(SupplierInvoiceStatus.APPROVED).build();
        });
        when(supplierInvoiceRepository.findById("S0")).thenReturn(Optional.of(draft));

        assertThat(service.preview("B1", null, d("20000"), d("4000")).getSupplierChargeOwed()).isEqualByComparingTo("20000");
        service.cancel("B1", cancel("20000", "4000"));

        verify(supplierInvoiceService, never()).cancelForBooking(eq("S0"), anyString());
        ArgumentCaptor<SupplierCreditNoteRequest> note = ArgumentCaptor.forClass(SupplierCreditNoteRequest.class);
        verify(supplierCreditNoteService).record(note.capture());
        assertThat(note.getValue().getTaxableValue()).as("the bill shrinks to the 20000 charge").isEqualByComparingTo("4000");
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("0");
    }

    /** A cancelled ticket's supplier takes its commission back: recalled on the bill before the credit note, and off the booking's profit. */
    @Test
    void theSupplierTakesItsCommissionBackOnACancelledTicket() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setCommission(d("410"));
        b.setProfit(d("1410"));
        // Paid the net (10,000 - 410 + 8.20); recalled, the bill owes its gross again.
        SupplierInvoice paid = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.PAID)
                .grandTotal(d("10000")).commission(d("410")).supplierTds(d("8.20")).amountPaid(d("9598.20"))
                .creditNoteTotal(BigDecimal.ZERO)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO).build();
        SupplierInvoice recalled = paid.toBuilder().commission(BigDecimal.ZERO).supplierTds(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(paid));
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(recalled));

        service.cancel("B1", cancel("2000", "0"));

        verify(supplierInvoiceService).recallCommission("S1");
        // Back from the supplier: what was paid less the 2,000 it kept.
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("7598.20");
        assertThat(b.getCommission()).isEqualByComparingTo("0");
        assertThat(b.getProfit()).isEqualByComparingTo("1000");
    }

    @Test
    void unpaidApprovedBillJustShrinksSoNothingComesBack() {
        Booking b = booking(BookingStatus.CONFIRMED);
        SupplierInvoice unpaid = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.APPROVED)
                .grandTotal(d("8000")).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(unpaid));

        service.cancel("B1", cancel("2000", "0"));

        verify(supplierCreditNoteService).record(any());
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("0");
    }

    @Test
    void onlyAPendingOrConfirmedBookingCanBeCancelled() {
        booking(BookingStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel("B1", cancel("0", "0"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customerPaymentCannotExceedWhatIsOutstanding() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        b.setRefundedTotalInr(d("5000"));

        assertThatThrownBy(() -> service.payCustomer("B1", pay("3000"))).isInstanceOf(IllegalArgumentException.class);
        verify(paymentReceiptService, never()).refundAdvance(any(), any(), any(), eq(false));
    }

    @Test
    void customerWithNoCreditNoteIsPaidFromTheAdvance() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of());

        service.payCustomer("B1", pay("2500"));

        verify(paymentReceiptService).refundAdvance(eq(b), any(), any(), eq(false));
    }

    @Test
    void supplierRefundWithNoCreditNoteToPostAgainstIsRejected() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("6000"));
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of());

        assertThatThrownBy(() -> service.recordSupplierRefund("B1", pay("6000")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("record the supplier bill first");
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("0");
        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void voidIsOnlyForAirlineTickets() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setType(com.voyra.crm.enums.BookingType.HOTEL);
        BookingCancelRequest r = cancel("0", "0");
        r.setType(CancellationType.VOID);

        assertThatThrownBy(() -> service.cancel("B1", r)).hasMessageContaining("Void is only for airline tickets");
        verify(bookingService, never()).markCancelled(any(), any());
    }

    @Test
    void typeIsStoredAndStandsInForAMissingReason() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setType(com.voyra.crm.enums.BookingType.FLIGHT);
        BookingCancelRequest r = cancel("500", "0");
        r.setType(CancellationType.VOID);
        r.setReason("  ");

        service.cancel("B1", r);

        verify(bookingService).markCancelled(b, "Void");
        assertThat(b.getCancellationType()).isEqualTo(CancellationType.VOID);
    }

    @Test
    void customerRefundToAccountGoesToTheWalletNotTheBank() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of());
        RefundSettlementRequest r = pay("7500");
        r.setPaymentMode(null);
        r.setToAccount(true);

        service.payCustomer("B1", r);

        ArgumentCaptor<RefundSettlementRequest> sent = ArgumentCaptor.forClass(RefundSettlementRequest.class);
        verify(paymentReceiptService).refundAdvance(eq(b), any(), sent.capture(), eq(true));
        assertThat(sent.getValue().getPaymentMode()).isEqualTo(PaymentMode.ADJUSTMENT);
        verify(paymentReceiptService).creditWallet(eq("C1"), eq(d("7500")), eq(LocalDate.of(2026, 10, 16)), anyString());
    }

    @Test
    void customerRefundToAccountWithACreditNoteRefundsItToWallet() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        com.voyra.crm.entity.CreditNote note = com.voyra.crm.entity.CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED)
                .refundableAmount(d("7500")).refundedAmount(BigDecimal.ZERO).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(note));
        RefundSettlementRequest r = pay("7500");
        r.setToAccount(true);

        service.payCustomer("B1", r);

        verify(creditNoteService).refund(eq("CN1"), any(), eq(true));
        verify(paymentReceiptService).creditWallet(eq("C1"), eq(d("7500")), any(), anyString());
    }

    @Test
    void supplierRefundToAccountIsKeptInTheSupplierWallet() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("6000"));
        SupplierCreditNote note = SupplierCreditNote.builder().id("SCN1").vendorId("V1")
                .refundableAmount(d("6000")).refundedAmount(BigDecimal.ZERO).build();
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(note));
        RefundSettlementRequest r = pay("6000");
        r.setToAccount(true);

        service.recordSupplierRefund("B1", r);

        verify(supplierCreditNoteService).receiveRefund(eq("SCN1"), any(), eq(true));
        verify(supplierPaymentService).creditWallet(eq("V1"), eq(d("6000")), any(), anyString());
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("6000");
    }

    @Test
    void settlingWithoutAModeOrToAccountIsRejected() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        RefundSettlementRequest r = pay("100");
        r.setPaymentMode(null);

        assertThatThrownBy(() -> service.payCustomer("B1", r)).hasMessageContaining("Payment mode is required");
    }

    @Test
    void crossCheckWaitsUntilBothSidesAreSettled() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        b.setRefundedTotalInr(d("5000"));
        assertThatThrownBy(() -> service.markChecked("B1")).isInstanceOf(IllegalStateException.class);

        b.setRefundedTotalInr(d("7500"));
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("U1", "Sandli"));
        service.markChecked("B1");

        assertThat(b.getRefundCheckedBy()).isEqualTo("Sandli");
        assertThat(b.getRefundCheckedAt()).isNotNull();
        assertThatThrownBy(() -> service.markChecked("B1")).hasMessageContaining("Already cross-checked");
    }

    /** BUG-MO-14: refunds settled, but the client has not paid the cancellation-charges invoice yet. */
    @Test
    void crossCheckWaitsForTheCancellationChargesToBePaid() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(BigDecimal.ZERO);
        b.setRefundedTotalInr(BigDecimal.ZERO);
        Invoice charges = Invoice.builder().id("CI1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).balanceDue(d("4680")).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(charges));

        assertThatThrownBy(() -> service.markChecked("B1")).hasMessageContaining("4680");
        assertThat(b.getRefundCheckedAt()).isNull();
        // ...and it is listed as charges to collect (the page keeps it off the cross-check tab until paid).
        b.setSupplierRefundExpected(BigDecimal.ZERO);
        b.setSupplierRefundReceived(BigDecimal.ZERO);
        when(bookingRepository.findByBookingStatus(BookingStatus.CANCELLED)).thenReturn(List.of(b));
        when(bookingService.toResponsePublic(b)).thenReturn(com.voyra.crm.dto.BookingResponse.builder().build());
        assertThat(service.listOutstanding().get(0).getChargesToCollectInr()).isEqualByComparingTo("4680");
    }

    @Test
    void settledButUncheckedCancellationsStayOnTheList() {
        Booking open = Booking.builder().id("B1").bookingStatus(BookingStatus.CANCELLED)
                .customerRefundDue(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO).build();
        Booking done = Booking.builder().id("B2").bookingStatus(BookingStatus.CANCELLED)
                .customerRefundDue(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO)
                .refundCheckedAt(java.time.LocalDateTime.now()).build();
        when(bookingRepository.findByBookingStatus(BookingStatus.CANCELLED)).thenReturn(List.of(open, done));
        when(bookingService.toResponsePublic(any())).thenAnswer(i -> com.voyra.crm.dto.BookingResponse.builder().build());

        service.listOutstanding();

        verify(bookingService).toResponsePublic(open);
        verify(bookingService, never()).toResponsePublic(done);
    }

    /** Nothing to refund either way, but the client still owes its cancellation charges: listed with what it owes. */
    @Test
    void aCancelledBookingWhoseClientStillOwesChargesIsListedWithWhatItOwes() {
        Booking owes = Booking.builder().id("B1").bookingStatus(BookingStatus.CANCELLED)
                .customerRefundDue(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO).build();
        when(bookingRepository.findByBookingStatus(BookingStatus.CANCELLED)).thenReturn(List.of(owes));
        when(bookingService.toResponsePublic(any())).thenAnswer(i -> com.voyra.crm.dto.BookingResponse.builder().build());
        Invoice charges = taxInvoice("I1", "4500");
        charges.setStatus(InvoiceLifecycle.ISSUED);
        charges.setBalanceDue(d("4680"));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(charges));

        assertThat(service.listOutstanding()).singleElement()
                .satisfies(r -> assertThat(r.getChargesToCollectInr()).isEqualByComparingTo("4680"));
    }

    @Test
    void negativeChargesAreRejectedForPreviewAndCancel() {
        booking(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.preview("B1", null, d("-5000"), BigDecimal.ZERO)).hasMessageContaining("negative");
        assertThatThrownBy(() -> service.cancel("B1", cancel("0", "-1"))).hasMessageContaining("negative");
        verify(bookingService, never()).markCancelled(any(), anyString());
    }

    // ---- supplier refund: the agent records it, Accounts confirms a bank one --------------

    private Booking awaitingSupplierRefund(String expected) {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d(expected));
        SupplierCreditNote note = SupplierCreditNote.builder().id("SCN1").vendorId("V1")
                .refundableAmount(d(expected)).refundedAmount(BigDecimal.ZERO).build();
        org.mockito.Mockito.lenient().when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED))
                .thenReturn(List.of(note));
        org.mockito.Mockito.lenient().when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Vikram"));
        return b;
    }

    private static RefundSettlementRequest bankClaim(String amount) {
        RefundSettlementRequest r = new RefundSettlementRequest();
        r.setAmount(d(amount));
        r.setPaidOn(LocalDate.of(2026, 10, 18));
        r.setReference("UTR9");
        return r;
    }

    @Test
    void aBankRefundIsOnlyAClaimUntilAccountsConfirmsIt() {
        Booking b = awaitingSupplierRefund("6000");

        service.recordSupplierRefund("B1", bankClaim("6000"));

        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("0");
        assertThat(b.getSupplierRefundClaimAmount()).isEqualByComparingTo("6000");
        assertThat(b.getSupplierRefundClaimReference()).isEqualTo("UTR9");
        assertThat(b.getSupplierRefundClaimBy()).isEqualTo("Vikram");
    }

    @Test
    void aClaimAboveWhatIsStillExpectedIsRejected() {
        awaitingSupplierRefund("6000");

        assertThatThrownBy(() -> service.recordSupplierRefund("B1", bankClaim("6001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outstanding");
    }

    @Test
    void aSecondClaimWhileOneIsOpenIsRejected() {
        awaitingSupplierRefund("6000");
        service.recordSupplierRefund("B1", bankClaim("3000"));

        assertThatThrownBy(() -> service.recordSupplierRefund("B1", bankClaim("1000")))
                .hasMessage("A bank refund is already waiting for the accountant's check");
    }

    @Test
    void confirmingTheClaimPostsItToTheBankAndClosesIt() {
        Booking b = awaitingSupplierRefund("6000");
        service.recordSupplierRefund("B1", bankClaim("6000"));
        RefundSettlementRequest confirm = bankClaim("6000");
        confirm.setPaymentMode(PaymentMode.BANK_TRANSFER);

        service.confirmSupplierRefund("B1", confirm);

        verify(supplierCreditNoteService).receiveRefund(eq("SCN1"), any(), eq(false));
        verify(supplierPaymentService, never()).creditWallet(any(), any(), any(), anyString());
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("6000");
        assertThat(b.getSupplierRefundClaimAmount()).isNull();
    }

    @Test
    void confirmingMoreThanTheClaimIsRejected() {
        awaitingSupplierRefund("6000");
        service.recordSupplierRefund("B1", bankClaim("4000"));
        RefundSettlementRequest confirm = bankClaim("5000");
        confirm.setPaymentMode(PaymentMode.BANK_TRANSFER);

        assertThatThrownBy(() -> service.confirmSupplierRefund("B1", confirm)).hasMessageContaining("the agent recorded");
        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void confirmingWithNoClaimIsRefused() {
        awaitingSupplierRefund("6000");
        RefundSettlementRequest confirm = bankClaim("6000");
        confirm.setPaymentMode(PaymentMode.BANK_TRANSFER);

        assertThatThrownBy(() -> service.confirmSupplierRefund("B1", confirm)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectingTheClaimDropsItWithoutPosting() {
        Booking b = awaitingSupplierRefund("6000");
        service.recordSupplierRefund("B1", bankClaim("6000"));

        service.rejectSupplierRefund("B1");

        assertThat(b.getSupplierRefundClaimAmount()).isNull();
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("0");
        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void crossCheckWaitsForAnOpenClaim() {
        Booking b = awaitingSupplierRefund("0");
        b.setSupplierRefundClaimAmount(d("100"));

        assertThatThrownBy(() -> service.markChecked("B1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aWalletRefundWaitsForAnOpenBankClaim() {
        awaitingSupplierRefund("8000");
        service.recordSupplierRefund("B1", bankClaim("8000"));
        RefundSettlementRequest wallet = bankClaim("8000");
        wallet.setToAccount(true);

        assertThatThrownBy(() -> service.recordSupplierRefund("B1", wallet))
                .hasMessage("A bank refund is already waiting for the accountant's check");
        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** Only the platform cancels a mirror and settles its supplier side; the agency's own customer refund stays the agency's. */
    @Test
    void crmEditOfAMirrorIs409() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setPlatformBookingId("PB1");

        assertThatThrownBy(() -> service.cancel("B1", cancel("1000", "0")))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class).hasMessage("Managed from Platform Bookings");
        b.setBookingStatus(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("6000"));
        assertThatThrownBy(() -> service.recordSupplierRefund("B1", pay("6000")))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class);
        assertThatThrownBy(() -> service.confirmSupplierRefund("B1", pay("6000")))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class);
        assertThatThrownBy(() -> service.rejectSupplierRefund("B1"))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class);
        assertThatThrownBy(() -> service.recordVoyraWalletRefund("B1", d("6000")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(supplierCreditNoteService, never()).record(any());
        verify(supplierCreditNoteService, never()).receiveRefund(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** The platform's cancel of a (even completed) mirror: the paid bill gets its supplier credit note, the refund lands on 1310. */
    @Test
    void theSystemActorCancelsAMirrorAndRecordsTheRefundAgainstTheVoyraWallet() {
        Booking b = booking(BookingStatus.COMPLETED);
        b.setPlatformBookingId("PB1");
        b.setReceivedTotalInr(BigDecimal.ZERO);
        SupplierInvoice paid = SupplierInvoice.builder().id("S1").vendorId("V-VOYRA").status(SupplierInvoiceStatus.PAID)
                .grandTotal(d("5400")).amountPaid(d("5400")).creditNoteTotal(BigDecimal.ZERO)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(paid));
        SupplierCreditNote note = SupplierCreditNote.builder().id("SCN1").vendorId("V-VOYRA")
                .refundableAmount(d("4000")).refundedAmount(BigDecimal.ZERO).build();
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(note));
        BookingCancelRequest request = cancel("1400", "0");
        request.setAirlineCharge(BigDecimal.ZERO);
        org.mockito.Mockito.doAnswer(i -> {
            b.setBookingStatus(BookingStatus.CANCELLED);
            return null;
        }).when(bookingService).markCancelled(eq(b), any());

        com.voyra.crm.security.SystemActor.call("T1", () -> {
            service.cancel("B1", request);
            return service.recordVoyraWalletRefund("B1", d("4000"));
        });

        ArgumentCaptor<SupplierCreditNoteRequest> credit = ArgumentCaptor.forClass(SupplierCreditNoteRequest.class);
        verify(supplierCreditNoteService).record(credit.capture());
        assertThat(credit.getValue().getTaxableValue()).isEqualByComparingTo("4000");
        ArgumentCaptor<SupplierCreditNoteRefundRequest> refund = ArgumentCaptor.forClass(SupplierCreditNoteRefundRequest.class);
        verify(supplierCreditNoteService).receiveRefund(eq("SCN1"), refund.capture(), eq(false));
        assertThat(refund.getValue().getPaymentMode()).isEqualTo(PaymentMode.VOYRA_WALLET);
        assertThat(refund.getValue().getAmount()).isEqualByComparingTo("4000");
        verify(supplierPaymentService, never()).creditWallet(any(), any(), any(), anyString());
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("4000");
    }
}
