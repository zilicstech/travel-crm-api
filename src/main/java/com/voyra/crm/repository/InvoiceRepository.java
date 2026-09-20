package com.voyra.crm.repository;

import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, String>, JpaSpecificationExecutor<Invoice> {

    /** At most one DRAFT tax invoice per booking, mirroring idx_invoice_booking_draft - checked
     *  here first for a clean 409 instead of surfacing a raw constraint violation. Several
     *  issued invoices against one booking are legal (advance + balance); only a second
     *  concurrent draft is not. */
    boolean existsByBookingIdAndDocumentTypeAndStatus(String bookingId, InvoiceDocumentType documentType, InvoiceLifecycle status);

    List<Invoice> findByBookingId(String bookingId);

    boolean existsByBookingId(String bookingId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /** Scoping helper: payment_receipt carries no agent_id column, so an Agent's receipt list is filtered by their invoice ids. */
    @Query("SELECT i.id FROM Invoice i WHERE i.agentId = :agentId")
    List<String> findIdsByAgentId(@Param("agentId") String agentId);

    /** AR ageing input: every non-cancelled tax invoice still carrying a balance, whichever client. */
    List<Invoice> findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(
            InvoiceDocumentType documentType, List<InvoiceLifecycle> statuses, BigDecimal zero);

    /** GST/TCS register and the dashboard's billed/output-tax figures: real tax invoices only, dated within range. */
    List<Invoice> findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
            InvoiceDocumentType documentType, InvoiceLifecycle excludedStatus, LocalDate from, LocalDate to);

    /** BookingAccountingSync input: every live (non-cancelled) tax invoice for a booking - several
     *  are now legal (advance + balance). Ordered by createdAt, not invoiceDate - invoiceDate is
     *  null until ISSUED (V19's own comment), which a DRAFT row in this set can still be. Oldest
     *  first, so {@code primaryInvoiceId} deterministically picks the earliest (the advance)
     *  rather than depending on undefined row order. */
    List<Invoice> findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
            String bookingId, InvoiceDocumentType documentType, InvoiceLifecycle excludedStatus);
}
