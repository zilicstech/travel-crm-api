package com.voyra.crm.entity;

import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A document the SUPPLIER issues to us - cancellation refund or a rate correction against one
 * {@link SupplierInvoice}. Carries the supplier's own note number ({@link #supplierNoteNumber}),
 * never ours (ARCHITECTURE-SPINE AD-7). No child line table, mirroring {@link CreditNote}.
 * {@link #retentionFee} is the supplier's own cancellation charge, held back from the credit.
 */
@Entity
@Table(name = "supplier_credit_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SupplierCreditNote {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "supplier_note_number", length = 60)
    private String supplierNoteNumber;

    @Column(name = "vendor_id", nullable = false, length = 36)
    private String vendorId;

    @Column(name = "vendor_name", nullable = false, length = 150)
    private String vendorName;

    @Column(name = "supplier_invoice_id", nullable = false, length = 36)
    private String supplierInvoiceId;

    @Column(name = "supplier_invoice_number", length = 60)
    private String supplierInvoiceNumber;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private SupplierCreditNoteReason reason;

    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupplierCreditNoteStatus status;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Column(name = "taxable_value", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal taxableValue = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "retention_fee", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal retentionFee = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "total_amount_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalAmountInr = BigDecimal.ZERO;

    @Column(name = "refundable_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal refundableAmount = BigDecimal.ZERO;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
