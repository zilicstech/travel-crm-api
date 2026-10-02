package com.voyra.crm.entity;

import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.BankTransactionMatchStatus;
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
 * One parsed line of an imported statement. Rule 4.3.2 - matching never posts directly; a tier-1
 * match or a confirmed tier-2 candidate calls the real {@code PaymentReceiptService}/
 * {@code SupplierPaymentService} posting path, and only THEN does this row's status move to
 * {@code TIER1_MATCHED}/{@code CONFIRMED} with {@link #matchedSourceId} pointing at the resulting
 * receipt/payment id.
 */
@Entity
@Table(name = "bank_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankTransaction {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "import_id", nullable = false, length = 36)
    private String importId;

    @Column(name = "bank_account_id", nullable = false, length = 36)
    private String bankAccountId;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private BankTransactionDirection direction;

    @Column(name = "running_balance", precision = 19, scale = 2)
    private BigDecimal runningBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 20)
    @Builder.Default
    private BankTransactionMatchStatus matchStatus = BankTransactionMatchStatus.UNMATCHED;

    @Enumerated(EnumType.STRING)
    @Column(name = "matched_source_type", length = 30)
    private BankMatchedSourceType matchedSourceType;

    @Column(name = "matched_source_id", length = 36)
    private String matchedSourceId;

    @Column(name = "match_score", precision = 5, scale = 2)
    private BigDecimal matchScore;

    @Column(name = "matched_at")
    private LocalDateTime matchedAt;

    @Column(name = "matched_by", length = 36)
    private String matchedBy;
}
