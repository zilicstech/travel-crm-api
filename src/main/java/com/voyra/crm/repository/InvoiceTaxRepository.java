package com.voyra.crm.repository;

import com.voyra.crm.entity.InvoiceTax;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceTaxRepository extends JpaRepository<InvoiceTax, String> {

    List<InvoiceTax> findByInvoiceIdOrderBySortOrderAsc(String invoiceId);

    void deleteByInvoiceId(String invoiceId);
}
