package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One debit or credit line of a {@link JournalEntry} - never both (DB check constraint
 * {@code chk_journal_line_one_side} mirrors the service-side assertion in
 * {@code service.JournalService#post}). {@code debitAmountInr}/{@code creditAmountInr} are the
 * only figures any statement reads; the foreign-currency pair is for dual-currency display and
 * revaluation only (Rule 1.2.2).
 */
@Entity
@Table(name = "journal_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class JournalLine {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "journal_entry_id", nullable = false, length = 36)
    private String journalEntryId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "account_code", nullable = false, length = 10)
    private String accountCode;

    @Column(name = "party_type", length = 20)
    private String partyType;

    @Column(name = "party_id", length = 36)
    private String partyId;

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

    @Column(name = "narration", length = 255)
    private String narration;
}
