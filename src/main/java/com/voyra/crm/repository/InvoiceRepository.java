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

    /** One live (non-cancelled) tax-invoice-track row per booking, mirroring the DB partial unique index - checked here first for a clean 409 instead of surfacing a raw constraint violation. */
    boolean existsByBookingIdAndDocumentTypeAndStatusNot(String bookingId, InvoiceDocumentType documentType, InvoiceLifecycle status);

    List<Invoice> findByBookingId(String bookingId);

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

    /** BookingAccountingSync input: the live (non-cancelled) tax invoice(s) for a booking - at most one, per the partial unique index. */
    List<Invoice> findByBookingIdAndDocumentTypeAndStatusNot(
            String bookingId, InvoiceDocumentType documentType, InvoiceLifecycle excludedStatus);
}
