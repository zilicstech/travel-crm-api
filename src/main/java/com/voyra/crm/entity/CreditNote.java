package com.voyra.crm.entity;

import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
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
 * A whole-or-part reversal against one issued tax invoice. There is no child line-item table
 * (blueprint §8.4 concern aside, this is a scope decision - see the architecture plan): a partial
 * credit is expressed as {@link #cancellationFee}, the slice of the invoice's taxable value the
 * agency retains and that stays taxable, with everything else reversed pro-rata at the invoice's
 * own persisted CGST/SGST/IGST/TCS amounts. {@link #currencyCode}/{@link #fxRateToInr} are always
 * copied from the invoice at draft time, never entered independently.
 */
@Entity
@Table(name = "credit_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CreditNote {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "credit_note_number", length = 40)
    private String creditNoteNumber;

    @Column(name = "financial_year", length = 9)
    private String financialYear;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "invoice_number", length = 40)
    private String invoiceNumber;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private CreditNoteReason reason;

    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditNoteStatus status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    private BigDecimal fxRateToInr;

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

    @Column(name = "tcs_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tcsAmount = BigDecimal.ZERO;

    @Column(name = "cancellation_fee", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cancellationFee = BigDecimal.ZERO;

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

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "issued_by", length = 36)
    private String issuedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "pdf_file_key", length = 500)
    private String pdfFileKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
