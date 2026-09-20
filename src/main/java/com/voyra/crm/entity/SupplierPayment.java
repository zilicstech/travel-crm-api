package com.voyra.crm.entity;

import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierPaymentDirection;
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
 * Append-only, mirrors {@link PaymentReceipt}. A mistake is corrected by a new row with
 * {@link #reversesPaymentId} pointing at this one and an opposite-signed {@link #amount}.
 * {@link #isAdvance} marks money paid out with no bill yet; {@link #appliedFromAdvance} marks a
 * row that only moves a bill's balance when an existing advance is applied to it - no money
 * actually moves, so {@code SupplierLedgerService} skips posting for those rows (AD-5).
 */
@Entity
@Table(name = "supplier_payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SupplierPayment {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "voucher_number", length = 40)
    private String voucherNumber;

    @Column(name = "financial_year", length = 9)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private SupplierPaymentDirection direction;

    @Column(name = "vendor_id", nullable = false, length = 36)
    private String vendorId;

    @Column(name = "vendor_name", nullable = false, length = 150)
    private String vendorName;

    @Column(name = "supplier_invoice_id", length = 36)
    private String supplierInvoiceId;

    @Column(name = "supplier_credit_note_id", length = 36)
    private String supplierCreditNoteId;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "amount_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amountInr = BigDecimal.ZERO;

    @Column(name = "tds_withheld", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tdsWithheld = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode;

    @Column(name = "instrument_ref", length = 100)
    private String instrumentRef;

    @Column(name = "bank_account_label", length = 150)
    private String bankAccountLabel;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "is_advance", nullable = false)
    @Builder.Default
    private Boolean isAdvance = false;

    @Column(name = "applied_from_advance", nullable = false)
    @Builder.Default
    private Boolean appliedFromAdvance = false;

    @Column(name = "reverses_payment_id", length = 36)
    private String reversesPaymentId;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversed_by", length = 36)
    private String reversedBy;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
