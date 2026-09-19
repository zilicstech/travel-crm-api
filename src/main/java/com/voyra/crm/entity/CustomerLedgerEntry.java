package com.voyra.crm.entity;

import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
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
 * One debit or credit row on a client's subsidiary receivables ledger. Append-only - see
 * {@code service.CustomerLedgerService}, the sole writer. The unique index on
 * {@code (source_type, source_id, entry_type)} (V21) is the idempotency guarantee: this entity
 * carries no in-code duplicate check because the constraint is the actual safety net.
 */
@Entity
@Table(name = "customer_ledger_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CustomerLedgerEntry {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private LedgerSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    @Column(name = "document_number", length = 40)
    private String documentNumber;

    @Column(name = "narration", nullable = false, length = 255)
    private String narration;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "debit_amount_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal debitAmountInr = BigDecimal.ZERO;

    @Column(name = "credit_amount_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal creditAmountInr = BigDecimal.ZERO;

    @Column(name = "reversed_entry_id", length = 36)
    private String reversedEntryId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
