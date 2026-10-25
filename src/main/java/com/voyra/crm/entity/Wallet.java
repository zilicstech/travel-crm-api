package com.voyra.crm.entity;

import com.voyra.crm.enums.WalletStatus;
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
import java.time.Instant;

/** One agency's prepaid wallet - public schema, mutated only under a row lock. available = balance - held. */
@Entity
@Table(name = "wallet")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Wallet {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    @Builder.Default
    private String currency = "INR";

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "held", nullable = false, precision = 19, scale = 2)
    private BigDecimal held;

    @Column(name = "is_test", nullable = false)
    @Builder.Default
    private Boolean isTest = false;

    @Column(name = "low_balance_threshold", precision = 19, scale = 2)
    private BigDecimal lowBalanceThreshold;

    @Column(name = "low_balance_notified_at")
    private Instant lowBalanceNotifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private WalletStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "closure_utr", length = 40)
    private String closureUtr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
