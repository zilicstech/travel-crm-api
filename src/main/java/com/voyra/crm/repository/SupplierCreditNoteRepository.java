package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplierCreditNoteRepository extends JpaRepository<SupplierCreditNote, String>, JpaSpecificationExecutor<SupplierCreditNote> {

    /** Caps a new credit note so the bill is never credited past its own grand total. */
    List<SupplierCreditNote> findBySupplierInvoiceIdAndStatus(String supplierInvoiceId, SupplierCreditNoteStatus status);

    List<SupplierCreditNote> findByVendorIdOrderByNoteDateDesc(String vendorId);

    boolean existsByBookingId(String bookingId);
}
