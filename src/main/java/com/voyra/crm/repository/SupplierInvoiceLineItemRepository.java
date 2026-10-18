package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierInvoiceLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplierInvoiceLineItemRepository extends JpaRepository<SupplierInvoiceLineItem, String> {

    List<SupplierInvoiceLineItem> findBySupplierInvoiceIdOrderBySortOrder(String supplierInvoiceId);

    @Modifying
    @Query(value = "UPDATE supplier_invoice_line_item SET deleted_at = now() WHERE supplier_invoice_id = :supplierInvoiceId AND deleted_at IS NULL", nativeQuery = true)
    void deleteBySupplierInvoiceId(@Param("supplierInvoiceId") String supplierInvoiceId);
}
