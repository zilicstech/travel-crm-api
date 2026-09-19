package com.voyra.crm.repository;

import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.enums.CreditNoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, String>, JpaSpecificationExecutor<CreditNote> {

    /** Used to cap a new credit note so the invoice is never credited past its own grand total. */
    List<CreditNote> findByInvoiceIdAndStatus(String invoiceId, CreditNoteStatus status);

    /** BookingAccountingSync.syncRefund input: every live credit note against this booking, whichever invoice it targets. */
    List<CreditNote> findByBookingIdAndStatusNot(String bookingId, CreditNoteStatus excludedStatus);

    /** BookingService.updateRefund guard: once a credit note exists, the manual refund PATCH can no longer contradict it. */
    boolean existsByBookingIdAndStatusNot(String bookingId, CreditNoteStatus excludedStatus);

    /** True-revenue input: issued credit notes in range net against the same period's billed taxable value. */
    List<CreditNote> findByStatusAndNoteDateBetween(CreditNoteStatus status, LocalDate from, LocalDate to);
}
