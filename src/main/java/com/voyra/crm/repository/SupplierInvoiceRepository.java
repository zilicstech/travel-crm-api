package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {

    /** supplier_invoice.supplier_name is a live reference stored as a string, not an FK - see VendorService. */
    @Modifying
    @Query("UPDATE SupplierInvoice s SET s.supplierName = :newName WHERE s.supplierName = :oldName")
    void updateSupplierName(@Param("oldName") String oldName, @Param("newName") String newName);
}
