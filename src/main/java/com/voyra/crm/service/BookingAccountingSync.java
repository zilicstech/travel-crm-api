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
    private final com.voyra.crm.repository.InvoiceLineItemRepository invoiceLineItemRepository;

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

        List<Invoice> liveInvoices = invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED);
        // An invoice billing several bookings counts for each by its share; its receipts are shared the same way.
        // Refunds stay with the booking they were paid for (a credit note's refund carries its booking).
        BigDecimal invoicedTotalInr = BigDecimal.ZERO;
        BigDecimal sharedReceivedInr = BigDecimal.ZERO;
        java.util.Set<String> shared = new java.util.HashSet<>();
        for (Invoice invoice : liveInvoices) {
            java.util.Map<String, com.voyra.crm.util.InvoiceShares.Share> shares = com.voyra.crm.util.InvoiceShares.of(
                    invoice.getBookingId(), invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
            BigDecimal grand = invoice.getGrandTotalInr() != null ? invoice.getGrandTotalInr() : BigDecimal.ZERO;
            if (shares.size() <= 1) {
                invoicedTotalInr = invoicedTotalInr.add(grand);
                continue;
            }
            shared.add(invoice.getId());
            invoicedTotalInr = invoicedTotalInr.add(com.voyra.crm.util.InvoiceShares.part(grand, shares, bookingId));
            // Each payment counts for the booking it was recorded for; one naming no booking on the invoice is shared.
            List<java.util.Map.Entry<String, BigDecimal>> received = paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(invoice.getId()).stream()
                    .filter(r -> r.getDirection() == ReceiptDirection.RECEIPT)
                    .map(r -> java.util.Map.entry(r.getBookingId() != null ? r.getBookingId() : "", r.getAmountInr())).toList();
            sharedReceivedInr = sharedReceivedInr.add(com.voyra.crm.util.InvoiceShares.attributed(bookingId, shares, received));
        }
        BigDecimal receivedTotalInr = sumInr(receipts(bookingId, ReceiptDirection.RECEIPT).stream()
                .filter(r -> r.getInvoiceId() == null || !shared.contains(r.getInvoiceId())).toList(), PaymentReceipt::getAmountInr)
                .add(sharedReceivedInr);
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

    /** An advance still on the client's account (no invoice yet) is not money received for the booking until an invoice draws it down. */
    /** One booking's own position on an invoice, in the invoice's currency: what it is billed and what was paid for it. */
    public record Position(BigDecimal billed, BigDecimal received) {
        public BigDecimal owed() {
            return billed.subtract(received);
        }
    }

    /**
     * On an invoice billing several bookings each keeps its own account: its share of the invoice, less its own
     * credit notes, against the payments recorded for it (refunds paid back off them). An invoice of one booking
     * is simply the invoice. {@code excludeCreditNoteId} leaves out a note being issued right now.
     */
    public Position positionOn(Invoice invoice, String bookingId, String excludeCreditNoteId) {
        java.util.Map<String, com.voyra.crm.util.InvoiceShares.Share> shares = com.voyra.crm.util.InvoiceShares.of(
                invoice.getBookingId(), invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()));
        List<java.util.Map.Entry<String, BigDecimal>> credits = creditNoteRepository.findByInvoiceIdAndStatus(invoice.getId(), CreditNoteStatus.ISSUED).stream()
                .filter(n -> !n.getId().equals(excludeCreditNoteId))
                .map(n -> java.util.Map.entry(n.getBookingId() != null ? n.getBookingId() : "", n.getTotalAmount())).toList();
        List<java.util.Map.Entry<String, BigDecimal>> paid = paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(invoice.getId()).stream()
                .map(r -> java.util.Map.entry(r.getBookingId() != null ? r.getBookingId() : "",
                        r.getDirection() == ReceiptDirection.REFUND ? r.getAmount().negate() : r.getAmount())).toList();
        BigDecimal grand = invoice.getGrandTotal() != null ? invoice.getGrandTotal() : BigDecimal.ZERO;
        BigDecimal share = shares.size() <= 1 ? grand : com.voyra.crm.util.InvoiceShares.part(grand, shares, bookingId);
        return new Position(share.subtract(com.voyra.crm.util.InvoiceShares.attributed(bookingId, shares, credits)),
                com.voyra.crm.util.InvoiceShares.attributed(bookingId, shares, paid));
    }

    private BigDecimal sumReceiptsInr(String bookingId, ReceiptDirection direction) {
        return sumInr(receipts(bookingId, direction), PaymentReceipt::getAmountInr);
    }

    private List<PaymentReceipt> receipts(String bookingId, ReceiptDirection direction) {
        return paymentReceiptRepository.findByBookingIdAndDirection(bookingId, direction).stream()
                .filter(r -> direction != ReceiptDirection.RECEIPT || !(r.getInvoiceId() == null && Boolean.TRUE.equals(r.getIsAdvance())))
                .toList();
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
