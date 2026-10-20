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

    List<InvoiceLineItem> findByInvoiceIdInOrderBySortOrderAsc(java.util.Collection<String> invoiceIds);

    /** Distinct bookings an invoice's lines bill, in line order. */
    @Query("SELECT l.bookingId FROM InvoiceLineItem l WHERE l.invoiceId = :invoiceId AND l.bookingId IS NOT NULL "
            + "GROUP BY l.bookingId ORDER BY MIN(l.sortOrder)")
    List<String> findBookingIdsByInvoiceId(@Param("invoiceId") String invoiceId);

    /** A draft's line list is replaced wholesale on every save - see InvoiceDocumentService. */
    @Modifying
    @Query(value = "UPDATE invoice_line_item SET deleted_at = now() WHERE invoice_id = :invoiceId AND deleted_at IS NULL", nativeQuery = true)
    void deleteByInvoiceId(@Param("invoiceId") String invoiceId);
}
