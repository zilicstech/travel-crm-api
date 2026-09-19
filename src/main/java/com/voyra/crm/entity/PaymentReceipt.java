package com.voyra.crm.entity;

import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
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
 * Append-only. A mistake is corrected by a new row with {@link #reversesReceiptId} pointing at
 * this one and an opposite-signed {@link #amount} - this row itself is only ever updated to stamp
 * {@link #reversedAt}/{@link #reversedBy}/{@link #reversalReason}. {@link #currencyCode} and
 * {@link #fxRateToInr} are always copied from the invoice at record time, never entered
 * independently - see {@code service.PaymentReceiptService}.
 */
@Entity
@Table(name = "payment_receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PaymentReceipt {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Column(name = "financial_year", length = 9)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private ReceiptDirection direction;

    @Column(name = "invoice_id", length = 36)
    private String invoiceId;

    @Column(name = "credit_note_id", length = 36)
    private String creditNoteId;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode;

    @Column(name = "instrument_ref", length = 100)
    private String instrumentRef;

    @Column(name = "bank_account_label", length = 150)
    private String bankAccountLabel;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "is_advance", nullable = false)
    @Builder.Default
    private Boolean isAdvance = false;

    @Column(name = "reverses_receipt_id", length = 36)
    private String reversesReceiptId;

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
