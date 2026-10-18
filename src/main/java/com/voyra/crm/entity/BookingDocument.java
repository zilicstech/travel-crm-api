package com.voyra.crm.entity;

import com.voyra.crm.enums.BookingDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A voucher document attached to a booking - an e-ticket, a hotel voucher, an insurance
 * certificate - each with its own reference and file. A booking can hold several. {@code
 * bookingId} is a plain string, not a JPA association (blueprint §8.4 - flat tables only).
 */
@Entity
@Table(name = "booking_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BookingDocument {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private BookingDocumentType docType;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "issued_date")
    private LocalDate issuedDate;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** What this voucher adds to the booking: its cost, and the selling price worked out from the markup. */
    @Column(name = "net_cost", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private java.math.BigDecimal netCost = java.math.BigDecimal.ZERO;

    @Column(name = "selling_price", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private java.math.BigDecimal sellingPrice = java.math.BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "markup_mode", length = 10)
    private com.voyra.crm.enums.MarkupMode markupMode;

    @Column(name = "markup_value", precision = 14, scale = 2)
    private java.math.BigDecimal markupValue;

    @Column(name = "vendor_id", length = 36)
    private String vendorId;

    /** The supplier bill this voucher drafted, withdrawn with the voucher while still unposted. */
    @Column(name = "supplier_invoice_id", length = 36)
    private String supplierInvoiceId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
