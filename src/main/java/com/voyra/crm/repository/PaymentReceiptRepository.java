package com.voyra.crm.repository;

import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.ReceiptDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, String>, JpaSpecificationExecutor<PaymentReceipt> {

    List<PaymentReceipt> findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(String invoiceId);

    /** Used at proforma -> tax-invoice conversion to re-point advance receipts onto the new row. */
    List<PaymentReceipt> findByInvoiceIdAndIsAdvanceTrue(String invoiceId);

    /** Dashboard "collected this period": every cash movement, advance or not, reversal or not - a reversal's negative amount nets itself out. */
    List<PaymentReceipt> findByDirectionAndReceivedOnBetween(ReceiptDirection direction, LocalDate from, LocalDate to);

    /** Dashboard "advance held": advance receipts not yet re-pointed onto a converted tax invoice. */
    List<PaymentReceipt> findByDirectionAndIsAdvanceTrue(ReceiptDirection direction);

    /** Sums to a credit note's cumulative refunded-out amount - see {@code CreditNoteService#applyRefundSettlement}. */
    List<PaymentReceipt> findByCreditNoteIdOrderByReceivedOnAscCreatedAtAsc(String creditNoteId);

    /** BookingAccountingSync input: bookingId is stable across a proforma -> tax-invoice conversion, unlike invoiceId. */
    List<PaymentReceipt> findByBookingIdAndDirection(String bookingId, ReceiptDirection direction);

    boolean existsByBookingId(String bookingId);
}
