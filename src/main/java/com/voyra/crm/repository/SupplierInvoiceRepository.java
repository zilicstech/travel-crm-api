package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {
}
