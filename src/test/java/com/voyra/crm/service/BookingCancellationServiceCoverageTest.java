package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCancelPreviewResponse;
import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.RefundSettlementRequest;
import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
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
import com.voyra.crm.security.SystemActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch coverage for BookingCancellationService under strict stubs. */
@ExtendWith(MockitoExtension.class)
class BookingCancellationServiceCoverageTest {

    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepository;
    @Mock BookingAccountingSync bookingAccountingSync;
    @Mock InvoiceRepository invoiceRepository;
    @Mock CreditNoteRepository creditNoteRepository;
    @Mock CreditNoteService creditNoteService;
    @Mock SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock SupplierInvoiceService supplierInvoiceService;
    @Mock SupplierCreditNoteRepository supplierCreditNoteRepository;
    @Mock SupplierCreditNoteService supplierCreditNoteService;
    @Mock PaymentReceiptService paymentReceiptService;
    @Mock SupplierPaymentService supplierPaymentService;
    @Mock AuthorResolver authorResolver;
    @Mock RevenueRecognitionTenantService revenueRecognitionTenantService;
    @Mock BookingInvoiceIssuer bookingInvoiceIssuer;
    @Mock InvoiceDocumentService invoiceDocumentService;
    @InjectMocks BookingCancellationService service;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Booking booking(BookingStatus status) {
        Booking b = Booking.builder().id("B1").clientId("C1").bookingStatus(status).type(BookingType.HOTEL)
                .receivedTotalInr(d("10000")).refundedTotalInr(BigDecimal.ZERO).build();
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        return b;
    }

    private static BookingCancelRequest cancel(CancellationType type, String reason) {
        BookingCancelRequest r = new BookingCancelRequest();
        r.setType(type);
        r.setReason(reason);
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

    // ------------------------------------------------------------------ preview / cancel

    @Test
    void withNothingKeptAndNoInvoiceThePreviewRefundsEverythingPaid() {
        booking(BookingStatus.CONFIRMED);

        BookingCancelPreviewResponse p = service.preview("B1", null, null, null);

        assertThat(p.getCustomerPaid()).isEqualByComparingTo("10000");
        assertThat(p.getCustomerRefundDue()).isEqualByComparingTo("10000");
        assertThat(p.getChargesInvoiceTotal()).isNull();
        assertThat(p.isInvoiced()).isFalse();
        assertThat(p.getSupplierRefundExpected()).isEqualByComparingTo("0");
        verify(bookingInvoiceIssuer, never()).cancellationChargesTotal(any(), any(), any());
    }

    @Test
    void aForeignInvoiceWithNoRateIsWorkedAtParAndOneAlreadyCreditedOrWithAnUnnotedCreditIsSkipped() {
        booking(BookingStatus.CONFIRMED);
        Invoice noRate = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).fxRateToInr(null).build();
        Invoice zeroRate = Invoice.builder().id("I2").bookingId("B1").status(InvoiceLifecycle.PAID).fxRateToInr(BigDecimal.ZERO).creditNoteTotal(null).build();
        Invoice creditedUnnoted = Invoice.builder().id("I3").bookingId("B1").status(InvoiceLifecycle.ISSUED).creditNoteTotal(d("10")).build();
        Invoice creditedForUs = Invoice.builder().id("I4").bookingId("B1").status(InvoiceLifecycle.ISSUED).build();
        Invoice draft = Invoice.builder().id("I5").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(noRate, zeroRate, creditedUnnoted, creditedForUs, draft));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of(
                CreditNote.builder().bookingId("B2").build()));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I2", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(creditNoteRepository.findByInvoiceIdAndStatus("I3", CreditNoteStatus.ISSUED)).thenReturn(List.of());
        when(creditNoteRepository.findByInvoiceIdAndStatus("I4", CreditNoteStatus.ISSUED)).thenReturn(List.of(
                CreditNote.builder().bookingId(null).build()));
        when(creditNoteService.shareOf(noRate, "B1")).thenReturn(new CreditNoteService.Share(d("6000"), BigDecimal.ONE));
        when(creditNoteService.shareOf(zeroRate, "B1")).thenReturn(new CreditNoteService.Share(d("4000"), BigDecimal.ONE));
        when(creditNoteService.previewRefundable(noRate, "B1", d("1000"), d("1000"))).thenReturn(d("5000"));
        when(creditNoteService.previewRefundable(zeroRate, "B1", d("0"), d("0"))).thenReturn(d("4000"));

        BookingCancelPreviewResponse p = service.preview("B1", null, d("1000"), null);

        assertThat(p.isInvoiced()).isTrue();
        assertThat(p.getCustomerRefundDue()).isEqualByComparingTo("9000");
    }

    @Test
    void aCancelWithNothingKeptBeforeAnyInvoiceRefundsAllAndNamesTheType() {
        Booking b = booking(BookingStatus.PENDING);
        b.setType(BookingType.FLIGHT);

        service.cancel("B1", cancel(CancellationType.VOID, " "));

        verify(bookingService).markCancelled(b, "Void");
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("10000");
        assertThat(b.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
        assertThat(b.getCancellationType()).isEqualTo(CancellationType.VOID);
        verify(bookingInvoiceIssuer, never()).issueCancellationCharges(any(), any(), any());
        verify(bookingRepository).save(b);
    }

    @Test
    void onlyAPendingOrConfirmedBookingOrAFinishedPlatformMirrorIsCancellable() {
        Booking completed = booking(BookingStatus.COMPLETED);

        assertThatThrownBy(() -> service.cancel("B1", cancel(CancellationType.CANCELLATION, null)))
                .hasMessage("Only a pending or confirmed booking can be cancelled");
        completed.setPlatformBookingId("PB1");
        assertThatThrownBy(() -> service.cancel("B1", cancel(CancellationType.CANCELLATION, null)))
                .hasMessage("Only a pending or confirmed booking can be cancelled");

        SystemActor.call("T1", () -> service.cancel("B1", cancel(CancellationType.CANCELLATION, "Trip over")));

        verify(bookingService).markCancelled(completed, "Trip over");
    }

    @Test
    void aCancelledMirrorWithNoSupplierCreditNoteLabelsTheRefundByBookingId() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setPlatformBookingId("PB1");
        b.setSupplierRefundExpected(d("100"));
        SupplierCreditNote spent = SupplierCreditNote.builder().id("SN0").vendorId("V1").refundableAmount(d("20")).refundedAmount(d("20")).build();
        SupplierCreditNote open = SupplierCreditNote.builder().id("SN1").vendorId("V1").refundableAmount(d("100")).refundedAmount(BigDecimal.ZERO).build();
        SupplierCreditNote later = SupplierCreditNote.builder().id("SN2").vendorId("V2").refundableAmount(d("100")).refundedAmount(BigDecimal.ZERO).build();
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(spent, open, later));
        RefundSettlementRequest r = pay("60");
        r.setToAccount(true);

        SystemActor.call("T1", () -> service.recordSupplierRefund("B1", r));

        ArgumentCaptor<SupplierCreditNoteRefundRequest> refund = ArgumentCaptor.forClass(SupplierCreditNoteRefundRequest.class);
        verify(supplierCreditNoteService).receiveRefund(eq("SN1"), refund.capture(), eq(true));
        assertThat(refund.getValue().getAmount()).isEqualByComparingTo("60");
        assertThat(refund.getValue().getPaymentMode()).isEqualTo(PaymentMode.ADJUSTMENT);
        verify(supplierCreditNoteService, never()).receiveRefund(eq("SN2"), any(), anyBoolean());
        verify(supplierPaymentService).creditWallet("V1", d("60"), LocalDate.of(2026, 10, 16), "Refund on cancelled booking (B1) kept in supplier wallet");
        assertThat(b.getSupplierRefundReceived()).isEqualByComparingTo("60");
    }

    // ------------------------------------------------------------------ supplier bills on cancel

    @Test
    void cancellingRecallsCommissionSkipsADraftThatStaysUnapprovedAndABillWhollyKept() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setCommission(d("50"));
        b.setProfit(d("2000"));
        SupplierInvoice withCommission = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.APPROVED)
                .commission(d("50")).grandTotal(d("300")).creditNoteTotal(BigDecimal.ZERO).amountPaid(d("300")).build();
        SupplierInvoice recalled = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.APPROVED)
                .grandTotal(d("300")).creditNoteTotal(BigDecimal.ZERO).amountPaid(d("300")).build();
        SupplierInvoice draft = SupplierInvoice.builder().id("S2").status(SupplierInvoiceStatus.DRAFT)
                .grandTotal(d("500")).creditNoteTotal(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(draft, withCommission));
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(recalled));
        when(supplierInvoiceService.approveForBooking("S2", "Supplier cancellation charge on a cancelled booking"))
                .thenReturn(SupplierInvoiceResponse.builder().status(SupplierInvoiceStatus.PENDING_APPROVAL).build());
        when(bookingInvoiceIssuer.issueCancellationCharges(b, d("400"), BigDecimal.ZERO)).thenReturn(null);
        BookingCancelRequest r = cancel(CancellationType.CANCELLATION, null);
        r.setSupplierCharge(d("400"));

        service.cancel("B1", r);

        verify(bookingService).markCancelled(b, "Cancellation");
        verify(supplierInvoiceService).recallCommission("S1");
        verify(supplierCreditNoteService, never()).record(any());
        assertThat(b.getSupplierRefundExpected()).isEqualByComparingTo("0");
        assertThat(b.getProfit()).isEqualByComparingTo("1950");
        assertThat(b.getCommission()).isEqualByComparingTo("0");
        // with no charges invoice issued the refund falls back to paid less what was kept
        assertThat(b.getCustomerRefundDue()).isEqualByComparingTo("9600");
    }

    // ------------------------------------------------------------------ customer refunds

    @Test
    void aRefundIsTakenFromOpenIssuedNotesThenTheHeldAdvanceAgainstALiveProforma() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("500"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        CreditNote draft = CreditNote.builder().id("CN0").status(CreditNoteStatus.DRAFT).refundableAmount(d("100")).build();
        CreditNote spent = CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED).refundableAmount(d("100")).refundedAmount(d("100")).build();
        CreditNote open = CreditNote.builder().id("CN2").status(CreditNoteStatus.ISSUED).refundableAmount(d("200")).refundedAmount(null).fxRateToInr(null).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(draft, spent, open));
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of(
                Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED).build(),
                Invoice.builder().id("PF1").documentType(InvoiceDocumentType.PROFORMA).status(InvoiceLifecycle.CANCELLED).build(),
                Invoice.builder().id("PF2").documentType(InvoiceDocumentType.PROFORMA).status(InvoiceLifecycle.PROFORMA_ISSUED).build()));

        service.payCustomer("B1", pay("500"));

        ArgumentCaptor<CreditNoteRefundRequest> noteRefund = ArgumentCaptor.forClass(CreditNoteRefundRequest.class);
        verify(creditNoteService).refund(eq("CN2"), noteRefund.capture(), eq(false));
        assertThat(noteRefund.getValue().getAmount()).isEqualByComparingTo("200");
        ArgumentCaptor<RefundSettlementRequest> rest = ArgumentCaptor.forClass(RefundSettlementRequest.class);
        verify(paymentReceiptService).refundAdvance(eq(b), eq("PF2"), rest.capture(), eq(false));
        assertThat(rest.getValue().getAmount()).isEqualByComparingTo("300");
        verify(paymentReceiptService, never()).creditWallet(any(), any(), any(), any());
        assertThat(b.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
    }

    @Test
    void aRefundUsedUpOnTheFirstNoteLeavesTheRestAndNeedsNoAdvance() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("300"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        CreditNote first = CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED).refundableAmount(d("300")).fxRateToInr(BigDecimal.ONE).build();
        CreditNote second = CreditNote.builder().id("CN2").status(CreditNoteStatus.ISSUED).refundableAmount(d("300")).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(first, second));

        service.payCustomer("B1", pay("300"));

        verify(creditNoteService, times(1)).refund(any(), any(), anyBoolean());
        verify(paymentReceiptService, never()).refundAdvance(any(), any(), any(), anyBoolean());
    }

    @Test
    void aRefundNeedsACancelledBookingAndAPaymentMode() {
        booking(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.payCustomer("B1", pay("1"))).hasMessage("This booking is not cancelled");

        booking(BookingStatus.CANCELLED);
        RefundSettlementRequest noMode = pay("1");
        noMode.setPaymentMode(null);
        assertThatThrownBy(() -> service.payCustomer("B1", noMode)).hasMessage("Payment mode is required");
    }

    // ------------------------------------------------------------------ supplier refunds

    @Test
    void aBankRefundWithABlankReferenceIsClaimedWithoutOne() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("100"));
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(
                SupplierCreditNote.builder().refundableAmount(d("100")).refundedAmount(BigDecimal.ZERO).build()));
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Agent A"));
        RefundSettlementRequest r = pay("100");
        r.setReference(" ");

        service.recordSupplierRefund("B1", r);

        assertThat(b.getSupplierRefundClaimAmount()).isEqualByComparingTo("100");
        assertThat(b.getSupplierRefundClaimReference()).isNull();
        assertThat(b.getSupplierRefundClaimBy()).isEqualTo("Agent A");
    }

    @Test
    void aBankRefundWithNoReferenceIsClaimedWithoutOne() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("100"));
        when(supplierCreditNoteRepository.findByBookingIdAndStatus("B1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(
                SupplierCreditNote.builder().refundableAmount(d("100")).refundedAmount(BigDecimal.ZERO).build()));
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Agent A"));
        RefundSettlementRequest r = pay("40");
        r.setReference(null);

        service.recordSupplierRefund("B1", r);

        assertThat(b.getSupplierRefundClaimReference()).isNull();
        assertThat(b.getSupplierRefundClaimAmount()).isEqualByComparingTo("40");
    }

    @Test
    void aRefundKeptOnAccountCreditsTheClientsWalletNamingTheBooking() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("200"));
        b.setDestination("Goa");
        b.setBookingNumber(7L);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        RefundSettlementRequest r = pay("200");
        r.setToAccount(true);

        service.payCustomer("B1", r);

        verify(paymentReceiptService).refundAdvance(eq(b), eq(null), any(), eq(true));
        verify(paymentReceiptService).creditWallet("C1", d("200"), LocalDate.of(2026, 10, 16),
                "Refund on cancelled booking Goa (BK00000007) kept on account");
    }

    @Test
    void thePreviewNeverRecallsCommission() {
        booking(BookingStatus.CONFIRMED);
        SupplierInvoice withCommission = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.APPROVED)
                .commission(d("50")).grandTotal(d("300")).creditNoteTotal(BigDecimal.ZERO).amountPaid(d("300")).build();
        SupplierInvoice draft = SupplierInvoice.builder().id("S2").status(SupplierInvoiceStatus.DRAFT)
                .grandTotal(d("100")).creditNoteTotal(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(draft, withCommission));

        BookingCancelPreviewResponse p = service.preview("B1", null, null, null);

        assertThat(p.getSupplierRefundExpected()).isEqualByComparingTo("300");
        assertThat(p.getPaidToSupplier()).isEqualByComparingTo("300");
        verify(supplierInvoiceService, never()).recallCommission(any());
        verify(supplierInvoiceService, never()).cancelForBooking(any(), any());
    }

    @Test
    void rejectingWithNoClaimIsRefused() {
        booking(BookingStatus.CANCELLED);

        assertThatThrownBy(() -> service.rejectSupplierRefund("B1")).hasMessage("No supplier refund is waiting for a bank check");
    }

    // ------------------------------------------------------------------ outstanding / cross-check

    @Test
    void theOutstandingListLeavesOutSettledCheckedBookingsAndAddsWhatTheClientOwes() {
        Booking settledChecked = Booking.builder().id("B1").refundCheckedAt(LocalDateTime.now()).build();
        Booking unchecked = Booking.builder().id("B2").build();
        Booking supplierOpen = Booking.builder().id("B3").refundCheckedAt(LocalDateTime.now()).supplierRefundExpected(d("10")).build();
        when(bookingRepository.findByBookingStatus(BookingStatus.CANCELLED)).thenReturn(List.of(settledChecked, unchecked, supplierOpen));
        com.voyra.crm.dto.BookingResponse r2 = com.voyra.crm.dto.BookingResponse.builder().id("B2").build();
        com.voyra.crm.dto.BookingResponse r3 = com.voyra.crm.dto.BookingResponse.builder().id("B3").build();
        when(bookingService.toResponsePublic(unchecked)).thenReturn(r2);
        when(bookingService.toResponsePublic(supplierOpen)).thenReturn(r3);
        Invoice own = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).balanceDue(d("300")).build();
        Invoice shared = Invoice.builder().id("I2").status(InvoiceLifecycle.PARTIALLY_PAID).fxRateToInr(d("2")).build();
        Invoice draft = Invoice.builder().id("I3").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B2", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(own, shared, draft));
        when(creditNoteService.shareOf(own, "B2")).thenReturn(new CreditNoteService.Share(d("1"), BigDecimal.ONE));
        when(creditNoteService.shareOf(shared, "B2")).thenReturn(new CreditNoteService.Share(d("1"), d("0.5")));
        when(bookingAccountingSync.positionOn(shared, "B2", null)).thenReturn(new BookingAccountingSync.Position(d("100"), d("40")));

        List<com.voyra.crm.dto.BookingResponse> list = service.listOutstanding();

        assertThat(list).containsExactly(r2, r3);
        assertThat(r2.getChargesToCollectInr()).isEqualByComparingTo("420.00");
        assertThat(r3.getChargesToCollectInr()).isEqualByComparingTo("0");
    }

    @Test
    void theCrossCheckWaitsForAClaimTheClientsChargesAndIsDoneOnce() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundClaimAmount(d("10"));
        assertThatThrownBy(() -> service.markChecked("B1")).hasMessage("Settle both the customer and the supplier refund before cross-checking");

        b.setSupplierRefundClaimAmount(null);
        Invoice owing = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).balanceDue(d("75")).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(owing), List.of());
        when(creditNoteService.shareOf(owing, "B1")).thenReturn(new CreditNoteService.Share(d("1"), BigDecimal.ONE));
        assertThatThrownBy(() -> service.markChecked("B1"))
                .hasMessage("The client still owes 75 on this booking's invoices - collect it before cross-checking");

        b.setRefundCheckedAt(LocalDateTime.now());
        b.setRefundCheckedBy("Neha");
        assertThatThrownBy(() -> service.markChecked("B1")).hasMessage("Already cross-checked by Neha");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void theRefundStateFollowsWhatWasPaidBack() {
        assertThat(BookingCancellationService.customerRefundState(BigDecimal.ZERO, d("5"))).isEqualTo(RefundState.NOT_APPLICABLE);
        assertThat(BookingCancellationService.customerRefundState(d("100"), null)).isEqualTo(RefundState.REFUND_PENDING);
        assertThat(BookingCancellationService.customerRefundState(d("100"), d("40"))).isEqualTo(RefundState.PARTIALLY_REFUNDED);
        assertThat(BookingCancellationService.customerRefundState(d("100"), d("100"))).isEqualTo(RefundState.REFUNDED);
    }
}
