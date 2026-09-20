package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String>, JpaSpecificationExecutor<SupplierInvoice> {

    /** supplier_invoice.supplier_name is a live reference stored as a string, not an FK - see VendorService. */
    @Modifying
    @Query("UPDATE SupplierInvoice s SET s.vendorName = :newName WHERE s.vendorName = :oldName")
    void updateSupplierName(@Param("oldName") String oldName, @Param("newName") String newName);

    boolean existsByBookingId(String bookingId);

    List<SupplierInvoice> findByStatusNotAndInvoiceDateBetween(SupplierInvoiceStatus excludedStatus, LocalDate from, LocalDate to);

    /** Cost reconciliation input: every non-cancelled bill against one booking, whichever vendor. */
    List<SupplierInvoice> findByBookingIdAndStatusNot(String bookingId, SupplierInvoiceStatus excludedStatus);

    long countByStatus(SupplierInvoiceStatus status);
}
