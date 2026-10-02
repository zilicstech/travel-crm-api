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
import java.time.LocalDateTime;

/**
 * A real bank account the agency holds, each tied to a child of {@code SystemAccount.BANK_ACCOUNTS}
 * ("1110") via {@link #ledgerAccountCode} (Rule 4.1.2) so a confirmed match names the right GL
 * account on the bank side of the entry.
 */
@Entity
@Table(name = "bank_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankAccount {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;

    @Column(name = "account_number", length = 40)
    private String accountNumber;

    @Column(name = "ifsc", length = 20)
    private String ifsc;

    @Column(name = "branch", length = 120)
    private String branch;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "ledger_account_code", nullable = false, length = 10)
    private String ledgerAccountCode;

    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
