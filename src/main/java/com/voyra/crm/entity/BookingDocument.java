package com.voyra.crm.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
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
@SQLDelete(sql = "UPDATE booking_document SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class BookingDocument implements com.voyra.crm.models.ForeignPricedRecord {

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

    /** What was typed, when the purchase price or a flat markup was in another currency - see util.FxMath.
     *  netCost and markupValue above are always the base-currency figures. */
    @Column(name = "purchase_currency", length = 3)
    private String purchaseCurrency;

    @Column(name = "purchase_price", precision = 14, scale = 2)
    private java.math.BigDecimal purchasePrice;

    @Column(name = "purchase_rate", precision = 18, scale = 6)
    private java.math.BigDecimal purchaseRate;

    @Column(name = "markup_currency", length = 3)
    private String markupCurrency;

    @Column(name = "markup_input", precision = 14, scale = 2)
    private java.math.BigDecimal markupInput;

    @Column(name = "markup_rate", precision = 18, scale = 6)
    private java.math.BigDecimal markupRate;

    @Column(name = "vendor_id", length = 36)
    private String vendorId;

    /** The fare's charges (basic, yq, yr, k3, oc, otherTax, processing, otherCharges) in the purchase currency;
     *  when set, the purchase price is their sum - see util.VoucherCharges. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "charges", columnDefinition = "jsonb")
    private java.util.Map<String, java.math.BigDecimal> charges;

    /** What the supplier pays the agency on this voucher (flights): PERCENT of basic or FLAT, as typed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "commission_mode", length = 10)
    private com.voyra.crm.enums.MarkupMode commissionMode;

    @Column(name = "commission_value", precision = 14, scale = 2)
    private java.math.BigDecimal commissionValue;

    /** TDS % the supplier deducts on the commission (sec. 194H). */
    @Column(name = "supplier_tds_rate", precision = 5, scale = 2)
    private java.math.BigDecimal supplierTdsRate;

    /** The commission and the supplier's TDS on it, in the purchase currency (as the supplier bill is). */
    @Column(name = "commission", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private java.math.BigDecimal commission = java.math.BigDecimal.ZERO;

    @Column(name = "supplier_tds", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private java.math.BigDecimal supplierTds = java.math.BigDecimal.ZERO;

    /** The commission in base - what it adds to the booking's commission and profit. */
    @Column(name = "commission_inr", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private java.math.BigDecimal commissionInr = java.math.BigDecimal.ZERO;

    /** The supplier bill this voucher drafted, withdrawn with the voucher while still unposted. */
    @Column(name = "supplier_invoice_id", length = 36)
    private String supplierInvoiceId;

    /** This voucher's own travel details (flight, stay, visa, transfer) - see util.VoucherDetails. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private java.util.Map<String, String> details;

    /** An air ticket's travellers (snapshots of the client's members) - see util.VoucherTrip. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "travellers", columnDefinition = "jsonb")
    private java.util.List<com.voyra.crm.dto.VoucherTraveller> travellers;

    /** An air ticket's flights in order; its flat flight details are worked out from them. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "legs", columnDefinition = "jsonb")
    private java.util.List<com.voyra.crm.dto.VoucherLeg> legs;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
