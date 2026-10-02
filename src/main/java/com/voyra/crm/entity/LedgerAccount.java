package com.voyra.crm.entity;

import com.voyra.crm.enums.ControlAccountOf;
import com.voyra.crm.enums.LedgerAccountType;
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

import java.time.LocalDateTime;

/**
 * One row in a tenant's chart of accounts. Seeded by {@code service.ChartOfAccountsSeedService}
 * (system accounts, {@code is_system=true}, never deleted - deactivated at most). A control
 * account ({@code is_control=true}) may never be the target of a {@code MANUAL} journal line -
 * {@code service.JournalService} enforces this (Rule 1.3.3).
 */
@Entity
@Table(name = "ledger_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LedgerAccount {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private LedgerAccountType accountType;

    @Column(name = "parent_code", length = 10)
    private String parentCode;

    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    @Column(name = "is_control", nullable = false)
    @Builder.Default
    private Boolean isControl = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_of", length = 30)
    private ControlAccountOf controlOf;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
