package com.voyra.crm.repository;

import com.voyra.crm.entity.InvoiceLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, String> {

    List<InvoiceLineItem> findByInvoiceIdOrderBySortOrderAsc(String invoiceId);

    /** A draft's line list is replaced wholesale on every save - see InvoiceDocumentService. */
    @Modifying
    @Query("DELETE FROM InvoiceLineItem l WHERE l.invoiceId = :invoiceId")
    void deleteByInvoiceId(@Param("invoiceId") String invoiceId);
}
