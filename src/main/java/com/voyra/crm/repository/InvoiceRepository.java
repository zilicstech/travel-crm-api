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

    /*
     * "An invoice of booking X" means any invoice with a line billing X (an invoice may cover several
     * bookings of one client), or whose first booking is X - every query below that takes a
     * bookingId reads it that way.
     */

    /** At most one DRAFT tax invoice holds a booking - checked by InvoiceDocumentService under the booking's row lock. */
    @Query("SELECT COUNT(i) > 0 FROM Invoice i WHERE (i.bookingId = :bookingId OR i.id IN (SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId = :bookingId)) AND i.documentType = :documentType AND i.status = :status")
    boolean existsByBookingIdAndDocumentTypeAndStatus(@Param("bookingId") String bookingId,
                                                       @Param("documentType") InvoiceDocumentType documentType,
                                                       @Param("status") InvoiceLifecycle status);

    @Query("SELECT i FROM Invoice i WHERE (i.bookingId = :bookingId OR i.id IN (SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId = :bookingId)) ORDER BY i.createdAt ASC")
    List<Invoice> findByBookingId(@Param("bookingId") String bookingId);

    @Query("SELECT COUNT(i) > 0 FROM Invoice i WHERE (i.bookingId = :bookingId OR i.id IN (SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId = :bookingId)) ")
    boolean existsByBookingId(@Param("bookingId") String bookingId);

    /** The live DRAFT tax invoice holding this booking, if any. */
    @Query("SELECT i FROM Invoice i WHERE (i.bookingId = :bookingId OR i.id IN (SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId = :bookingId)) AND i.documentType = com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE "
            + "AND i.status = com.voyra.crm.enums.InvoiceLifecycle.DRAFT ORDER BY i.createdAt ASC")
    List<Invoice> findDraftsForBooking(@Param("bookingId") String bookingId);

    /** The latest date already issued in a series (category + financial year) - a new invoice is never dated before it. */
    @Query("SELECT MAX(i.invoiceDate) FROM Invoice i WHERE i.serviceCategory = :category AND i.financialYear = :financialYear "
            + "AND i.documentType = com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE AND i.invoiceNumber IS NOT NULL "
            + "AND i.uploadedFileName IS NULL")
    LocalDate latestIssuedDate(@Param("category") com.voyra.crm.enums.InvoiceServiceCategory category,
                               @Param("financialYear") String financialYear);

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
    @Query("SELECT i FROM Invoice i WHERE (i.bookingId = :bookingId OR i.id IN (SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId = :bookingId)) AND i.documentType = :documentType AND i.status <> :excludedStatus "
            + "ORDER BY i.createdAt ASC")
    List<Invoice> findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
            @Param("bookingId") String bookingId, @Param("documentType") InvoiceDocumentType documentType,
            @Param("excludedStatus") InvoiceLifecycle excludedStatus);

    /** Revenue recognition job input (ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 2.3/2.4) - every
     *  live, invoiced sale against a booking whose departure has arrived. */
    @Query("SELECT i FROM Invoice i WHERE (i.bookingId IN :bookingIds OR i.id IN "
            + "(SELECT l.invoiceId FROM InvoiceLineItem l WHERE l.bookingId IN :bookingIds)) AND i.status IN :statuses")
    List<Invoice> findByBookingIdInAndStatusIn(@Param("bookingIds") List<String> bookingIds,
                                               @Param("statuses") List<InvoiceLifecycle> statuses);

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

    /** Outstanding on live tax invoices (ISSUED / PARTIALLY_PAID), in INR - the owner dashboard's pending payments. */
    @Query("SELECT COALESCE(SUM(i.balanceDueInr), 0) FROM Invoice i "
            + "WHERE i.documentType = com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE "
            + "AND i.status IN (com.voyra.crm.enums.InvoiceLifecycle.ISSUED, com.voyra.crm.enums.InvoiceLifecycle.PARTIALLY_PAID) "
            + "AND i.balanceDueInr > 0")
    BigDecimal sumOutstandingTaxInvoicesInr();

    /** Unpaid tax invoices falling due in a window - the calendar's invoice-due events. */
    List<Invoice> findByDocumentTypeAndStatusInAndDueDateBetween(
            InvoiceDocumentType documentType, List<InvoiceLifecycle> statuses,
            java.time.LocalDate from, java.time.LocalDate to);
}
