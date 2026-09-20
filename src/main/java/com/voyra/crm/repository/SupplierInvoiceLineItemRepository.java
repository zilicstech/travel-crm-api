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
    @Query("DELETE FROM SupplierInvoiceLineItem l WHERE l.supplierInvoiceId = :supplierInvoiceId")
    void deleteBySupplierInvoiceId(@Param("supplierInvoiceId") String supplierInvoiceId);
}
