package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.RefundSettlementRequest;
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
        when(supplierInvoiceService.approve(eq("S0"), anyString())).thenAnswer(inv -> {
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
    void typeIsStoredAndStandsInForAMissingReason() {
        Booking b = booking(BookingStatus.CONFIRMED);
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
        com.voyra.crm.entity.CreditNote note = com.voyra.crm.entity.CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED).build();
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

        service.listOutstanding();

        verify(bookingService).toResponsePublic(open);
        verify(bookingService, never()).toResponsePublic(done);
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
}
