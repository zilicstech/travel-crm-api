package com.voyra.crm.service;

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
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(List.of());
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
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of());
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatusSource()).isEqualTo(PaymentStatusSource.DERIVED);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(booking.getInvoicedTotalInr()).isEqualByComparingTo("1000.00");
        assertThat(booking.getPrimaryInvoiceId()).isEqualTo("I1");
    }

    @Test
    void partialReceiptsComputePartial() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("400.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(List.of(invoice));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.RECEIPT)).thenReturn(List.of(receipt));
        when(paymentReceiptRepository.findByBookingIdAndDirection("B1", ReceiptDirection.REFUND)).thenReturn(List.of());

        bookingAccountingSync.syncPayment("B1");

        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
    }

    @Test
    void fullReceiptsComputePaid() {
        Booking booking = manualBooking();
        Invoice invoice = Invoice.builder().id("I1").grandTotalInr(new BigDecimal("1000.00")).build();
        PaymentReceipt receipt = PaymentReceipt.builder().amountInr(new BigDecimal("1000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(List.of(invoice));
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
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(List.of(invoice));
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
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(any(), any(), any())).thenReturn(List.of());
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
}
