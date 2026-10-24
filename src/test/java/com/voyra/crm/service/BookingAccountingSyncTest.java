package com.voyra.crm.service;

import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingAccountingSyncTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private CreditNoteRepository creditNoteRepository;
    @Mock
    private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock
    private InvoiceLineSource invoiceLineSource;

    @org.junit.jupiter.api.BeforeEach
    void nothingAddedSinceTheInvoice() {
        org.mockito.Mockito.lenient().when(invoiceLineSource.invoiced(any()))
                .thenReturn(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of(), null));
    }


    @InjectMocks
    private BookingAccountingSync bookingAccountingSync;

    private Booking manualBooking() {
        return Booking.builder().id("B1").paymentStatusSource(PaymentStatusSource.MANUAL)
                .paymentStatus(PaymentStatus.PENDING).build();
    }

    @Test
    void anAdvanceReceiptWithNoTaxInvoiceYetNeverFlipsSourceOrTouchesPaymentStatus() {
        Booking booking = manualBooking();
        booking.setPaymentStatus(PaymentStatus.PAID); // simulate a prior manual value
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection(any(), any())).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatusSource()).isEqualTo(PaymentStatusSource.MANUAL);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PAID); // untouched
    }

    @Test
    void aLiveTaxInvoiceFlipsSourceToDerivedAndComputesPending() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatusSource()).isEqualTo(PaymentStatusSource.DERIVED);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(booking.getInvoicedTotalInr()).isEqualByComparingTo("1000.00");
        assertThat(booking.getPrimaryInvoiceId()).isEqualTo("I1");
    }

    /** An advance still held on the client's account for this booking is not "received" until its invoice draws it down. */
    @Test
    void anAdvanceStillHeldOnAccountIsNotCountedAsReceived() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt held = PaymentReceipt.builder().invoiceId(null).isAdvance(true).amountInr(new BigDecimal("700.00")).build();
        PaymentReceipt applied = PaymentReceipt.builder().invoiceId("I1").appliedFromAdvance(true).amountInr(new BigDecimal("300.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(held, applied));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getReceivedTotalInr()).isEqualByComparingTo("300.00");
    }

    /** Regression run 7 follow-up: a refund paid out of a held advance has the same no-invoice shape but must still count as refunded. */
    @Test
    void aRefundPaidOutOfAHeldAdvanceCountsAsRefunded() {
        Booking booking = manualBooking();
        PaymentReceipt refund = PaymentReceipt.builder().invoiceId(null).isAdvance(true).direction(ReceiptDirection.REFUND)
                .amountInr(new BigDecimal("1500.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of(refund));

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getRefundedTotalInr()).isEqualByComparingTo("1500.00");
    }

    @Test
    void partialReceiptsComputePartial() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("400.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(receipt));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
    }

    /** Regression C1: a voucher added after a fully paid invoice is still to collect, so the booking is not PAID. */
    @Test
    void aVoucherAddedSinceAFullyPaidInvoiceLeavesTheBookingPartial() {
        Booking booking = manualBooking();
        booking.setSellingPrice(new BigDecimal("13500.00"));
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("12360.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("12360.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(receipt));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());
        when(invoiceLineSource.invoiced("B1")).thenReturn(new InvoiceLineSource.Invoiced(new BigDecimal("12000.00"), java.util.Set.of(), null));

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
    }

    @Test
    void fullReceiptsComputePaid() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("1000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(receipt));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void anyRefundOverridesToRefundedRegardlessOfReceivedAmount() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("1000.00")).build();
        PaymentReceipt refund = PaymentReceipt.builder().amountInr(new BigDecimal("300.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(receipt));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of(refund));

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void onceDerivedACancelledInvoiceComputesPendingRatherThanStayingStale() {
        Booking booking = manualBooking();
        booking.setPaymentStatusSource(PaymentStatusSource.DERIVED);
        booking.setPaymentStatus(PaymentStatus.PAID);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection(any(), any())).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatusSource()).isEqualTo(PaymentStatusSource.DERIVED);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void noLiveCreditNoteLeavesRefundStateUntouched() {
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of());

        bookingAccountingSync.syncRefund("B1");

        org.mockito.Mockito.verifyNoInteractions(bookingRepository);
    }

    @Test
    void aLiveCreditNoteWithNoRefundYetIsRefundPending() {
        Booking booking = Booking.builder().id("B1").refundState(RefundState.NOT_APPLICABLE).build();
        CreditNote note = CreditNote.builder().status(CreditNoteStatus.ISSUED).totalAmountInr(new BigDecimal("500.00")).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(note));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncRefund("B1");

        assertThat(booking.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
        assertThat(booking.getRefundAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void aPartialRefundIsPartiallyRefunded() {
        Booking booking = Booking.builder().id("B1").build();
        CreditNote note = CreditNote.builder().status(CreditNoteStatus.ISSUED).totalAmountInr(new BigDecimal("500.00")).build();
        PaymentReceipt refund = PaymentReceipt.builder().amountInr(new BigDecimal("200.00")).receivedOn(LocalDate.of(2026, 9, 19)).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(note));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of(refund));

        bookingAccountingSync.syncRefund("B1");

        assertThat(booking.getRefundState()).isEqualTo(RefundState.PARTIALLY_REFUNDED);
        assertThat(booking.getRefundAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void aFullRefundIsRefundedAndStampsRefundedAt() {
        Booking booking = Booking.builder().id("B1").build();
        CreditNote note = CreditNote.builder().status(CreditNoteStatus.ISSUED).totalAmountInr(new BigDecimal("500.00")).build();
        PaymentReceipt refund = PaymentReceipt.builder().amountInr(new BigDecimal("500.00")).receivedOn(LocalDate.of(2026, 9, 19)).build();
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(note));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of(refund));

        bookingAccountingSync.syncRefund("B1");

        assertThat(booking.getRefundState()).isEqualTo(RefundState.REFUNDED);
        assertThat(booking.getRefundedAt()).isNotNull();
    }

    /** An invoice billing two bookings counts for each by its share; a payment naming no booking on it is shared the same way. */
    @Test
    void aSharedInvoiceAndItsReceiptsCountForEachBookingByShare() {
        Booking b2 = Booking.builder().id("B2").paymentStatusSource(PaymentStatusSource.MANUAL).paymentStatus(PaymentStatus.PENDING).build();
        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").grandTotalInr(new BigDecimal("10000.00")).build();
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(b2));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("7500")).build(),
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("2500")).build()));
        // Paid 4,000 against the invoice, naming no booking.
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().invoiceId("I1").direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("4000.00")).build()));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B2", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B2", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B2");

        assertThat(b2.getInvoicedTotalInr()).isEqualByComparingTo("2500.00");
        assertThat(b2.getReceivedTotalInr()).isEqualByComparingTo("1000.00");
        assertThat(b2.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
    }

    /** A payment recorded for one booking of a shared invoice counts for that booking alone. */
    @Test
    void aPaymentRecordedForOneBookingCountsForItAlone() {
        Booking b2 = Booking.builder().id("B2").paymentStatusSource(PaymentStatusSource.MANUAL).paymentStatus(PaymentStatus.PENDING).build();
        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").grandTotalInr(new BigDecimal("10000.00")).build();
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(b2));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("5000")).build(),
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("5000")).build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().invoiceId("I1").bookingId("B1").direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("5000.00")).build()));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B2", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B2", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B2");

        assertThat(b2.getReceivedTotalInr()).isEqualByComparingTo("0");
        assertThat(b2.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /** 44,720 for A and B; A credited 17,680 and paid 11,180, B paid 2,340: A is owed 6,500 back, B still owes 20,020. */
    @Test
    void eachBookingKeepsItsOwnAccountOnASharedInvoice() {
        Invoice invoice = Invoice.builder().id("I1").bookingId("A").grandTotal(new BigDecimal("44720.00")).build();
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("A").taxableValue(new BigDecimal("22000")).build(),
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B").taxableValue(new BigDecimal("22000")).build()));
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", com.voyra.crm.enums.CreditNoteStatus.ISSUED)).thenReturn(List.of(
                com.voyra.crm.entity.CreditNote.builder().id("CN1").bookingId("A").totalAmount(new BigDecimal("17680.00")).build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().bookingId("A").direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("11180.00")).build(),
                PaymentReceipt.builder().bookingId("B").direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("2340.00")).build()));

        assertThat(bookingAccountingSync.positionOn(invoice, "A", null).owed()).isEqualByComparingTo("-6500.00");
        assertThat(bookingAccountingSync.positionOn(invoice, "B", null).owed()).isEqualByComparingTo("20020.00");
        // Leaving out A's credit note (the one being issued) puts A back at its full share.
        assertThat(bookingAccountingSync.positionOn(invoice, "A", "CN1").billed()).isEqualByComparingTo("22360.00");
    }

    /** A refund on one booking's credit note stays with that booking: the other one on the invoice is not "refunded". */
    @Test
    void aRefundStaysWithTheBookingItWasPaidFor() {
        Booking b1 = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").grandTotalInr(new BigDecimal("10000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(invoice));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("5000")).build(),
                com.voyra.crm.entity.InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("5000")).build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().invoiceId("I1").bookingId("B1").direction(ReceiptDirection.RECEIPT).amountInr(new BigDecimal("10000.00")).build(),
                PaymentReceipt.builder().invoiceId("I1").bookingId("B2").direction(ReceiptDirection.REFUND).amountInr(new BigDecimal("3000.00")).build()));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(b1.getRefundedTotalInr()).isEqualByComparingTo("0");
        assertThat(b1.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }
}
