package com.voyra.crm.repository;

import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, String>, JpaSpecificationExecutor<Invoice> {

    /** One live (non-cancelled) tax-invoice-track row per booking, mirroring the DB partial unique index - checked here first for a clean 409 instead of surfacing a raw constraint violation. */
    boolean existsByBookingIdAndDocumentTypeAndStatusNot(String bookingId, InvoiceDocumentType documentType, InvoiceLifecycle status);

    List<Invoice> findByBookingId(String bookingId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
}
