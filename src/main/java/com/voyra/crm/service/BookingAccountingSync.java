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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Recomputes a booking's derived accounting facts from real invoices/receipts/credit notes.
 * Deliberately NOT {@code REQUIRES_NEW}: both methods are meant to run inside the same
 * transaction as the accounting mutation that triggered them (invoice issue, a receipt, a
 * credit note), so a rollback there undoes the booking's derived state with it - the same
 * "join the caller's transaction" rule {@code CustomerLedgerService.post} follows.
 *
 * <p>{@link #syncPayment} owns {@code paymentStatus}/{@code paymentStatusSource} plus the three
 * {@code *_total_inr} mirror columns; {@link #syncRefund} owns {@code refundState}/
 * {@code refundAmount}/{@code refundedAt}. Neither ever sets {@link RefundState#REFUND_DENIED} -
 * that stays a human decision with no accounting fact behind it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAccountingSync {

    private final BookingRepository bookingRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final CreditNoteRepository creditNoteRepository;

    /**
     * {@code paymentStatusSource} flips MANUAL -&gt; DERIVED the moment a live tax invoice first
     * exists for this booking, and this method never flips it back - even once that invoice is
     * later cancelled. While still MANUAL (no tax invoice has ever existed yet, e.g. only a
     * proforma advance has been recorded), {@code paymentStatus} itself is left untouched so an
     * owner's manual value on an as-yet-uninvoiced booking is never silently overwritten.
     */
    @Transactional
    public void syncPayment(String bookingId) {
        if (bookingId == null) {
            return;
        }
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return;
        }

        List<Invoice> liveInvoices = invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNot(
                bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED);
        BigDecimal invoicedTotalInr = sumInr(liveInvoices, Invoice::getGrandTotalInr);
        BigDecimal receivedTotalInr = sumReceiptsInr(bookingId, ReceiptDirection.RECEIPT);
        BigDecimal refundedTotalInr = sumReceiptsInr(bookingId, ReceiptDirection.REFUND);

        booking.setInvoicedTotalInr(invoicedTotalInr);
        booking.setReceivedTotalInr(receivedTotalInr);
        booking.setRefundedTotalInr(refundedTotalInr);
        booking.setPrimaryInvoiceId(liveInvoices.isEmpty() ? null : liveInvoices.get(0).getId());

        if (invoicedTotalInr.compareTo(BigDecimal.ZERO) > 0) {
            booking.setPaymentStatusSource(PaymentStatusSource.DERIVED);
            booking.setPaymentStatus(deriveStatus(invoicedTotalInr, receivedTotalInr, refundedTotalInr));
        } else if (booking.getPaymentStatusSource() == PaymentStatusSource.DERIVED) {
            // Was invoiced, that invoice is now cancelled: nothing currently billed on it.
            booking.setPaymentStatus(PaymentStatus.PENDING);
        }
        // Still MANUAL and no live invoice ever existed: leave paymentStatus exactly as a human set it.

        bookingRepository.save(booking);
        log.info("Booking payment synced: bookingId={}, invoiced={}, received={}, refunded={}, status={}",
                bookingId, invoicedTotalInr, receivedTotalInr, refundedTotalInr, booking.getPaymentStatus());
    }

    /**
     * {@code refundState}/{@code refundAmount} only move once a live (non-cancelled) credit note
     * exists against this booking - withdrawing the only credit note that justified a refund
     * state leaves it as-is rather than silently reverting it, since a booking-level cancellation
     * may have set {@code REFUND_PENDING} for reasons unrelated to that one credit note.
     */
    @Transactional
    public void syncRefund(String bookingId) {
        if (bookingId == null) {
            return;
        }
        List<CreditNote> liveNotes = creditNoteRepository.findByBookingIdAndStatusNot(bookingId, CreditNoteStatus.CANCELLED)
                .stream().filter(n -> n.getStatus() == CreditNoteStatus.ISSUED).toList();
        if (liveNotes.isEmpty()) {
            return;
        }
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return;
        }

        BigDecimal refundableTotalInr = sumInr(liveNotes, CreditNote::getTotalAmountInr);
        List<PaymentReceipt> refunds = paymentReceiptRepository.findByBookingIdAndDirection(bookingId, ReceiptDirection.REFUND);
        BigDecimal refundedTotalInr = sumInr(refunds, PaymentReceipt::getAmountInr);

        booking.setRefundAmount(refundedTotalInr);
        if (refundedTotalInr.compareTo(BigDecimal.ZERO) <= 0) {
            booking.setRefundState(RefundState.REFUND_PENDING);
        } else if (refundedTotalInr.compareTo(refundableTotalInr) < 0) {
            booking.setRefundState(RefundState.PARTIALLY_REFUNDED);
        } else {
            booking.setRefundState(RefundState.REFUNDED);
            refunds.stream().map(PaymentReceipt::getReceivedOn).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .ifPresent(latest -> booking.setRefundedAt(latest.atStartOfDay()));
        }

        bookingRepository.save(booking);
        log.info("Booking refund synced: bookingId={}, refundable={}, refunded={}, refundState={}",
                bookingId, refundableTotalInr, refundedTotalInr, booking.getRefundState());
    }

    private BigDecimal sumReceiptsInr(String bookingId, ReceiptDirection direction) {
        return sumInr(paymentReceiptRepository.findByBookingIdAndDirection(bookingId, direction), PaymentReceipt::getAmountInr);
    }

    private static PaymentStatus deriveStatus(BigDecimal invoicedTotalInr, BigDecimal receivedTotalInr, BigDecimal refundedTotalInr) {
        if (refundedTotalInr.compareTo(BigDecimal.ZERO) > 0) {
            return PaymentStatus.REFUNDED;
        }
        if (receivedTotalInr.compareTo(invoicedTotalInr) >= 0) {
            return PaymentStatus.PAID;
        }
        if (receivedTotalInr.compareTo(BigDecimal.ZERO) > 0) {
            return PaymentStatus.PARTIAL;
        }
        return PaymentStatus.PENDING;
    }

    private static <T> BigDecimal sumInr(List<T> items, java.util.function.Function<T, BigDecimal> extractor) {
        return items.stream().map(extractor).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
