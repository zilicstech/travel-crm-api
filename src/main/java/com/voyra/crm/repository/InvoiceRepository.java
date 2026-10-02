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

    /** Bank-reconciliation tier-1 amount narrowing (Rule 4.3) - open invoices whose outstanding balance equals the bank line's amount exactly. */
    List<Invoice> findByStatusInAndBalanceDueInr(List<InvoiceLifecycle> statuses, BigDecimal balanceDueInr);

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

    /** Revenue recognition job input (ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 2.3/2.4) - every
     *  live, invoiced sale against a booking whose departure has arrived. */
    List<Invoice> findByBookingIdInAndStatusIn(List<String> bookingIds, List<InvoiceLifecycle> statuses);

    /**
     * TCS threshold accumulation input ({@code TaxEngine#compute}): this client's overseas-package
     * consideration already booked this financial year, excluding DRAFT (never issued, so not yet
     * a real sale) and CANCELLED. A still-DRAFT invoice is naturally excluded without needing an
     * explicit "exclude this id" parameter - it only joins the accumulation once it is actually
     * issued, by which point a later invoice's preview correctly sees it.
     */
    @Query("SELECT COALESCE(SUM(i.taxableValueInr), 0) FROM Invoice i "
            + "WHERE i.clientId = :clientId AND i.supplyNature = :supplyNature AND i.financialYear = :financialYear "
            + "AND i.status NOT IN (com.voyra.crm.enums.InvoiceLifecycle.DRAFT, com.voyra.crm.enums.InvoiceLifecycle.CANCELLED)")
    BigDecimal sumConsiderationForClientInFy(@Param("clientId") String clientId,
                                              @Param("supplyNature") com.voyra.crm.enums.SupplyNature supplyNature,
                                              @Param("financialYear") String financialYear);
}
