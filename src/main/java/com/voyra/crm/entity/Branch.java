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

import java.time.LocalDateTime;

/**
 * An intra-agency partition, not a tenant - tenancy stays schema-per-agency
 * (ACCOUNTING_EXPANSION_ARCHITECTURE.md §6, Decision 6). Columns only in this phase: nothing
 * populates or filters on a booking/invoice/bill's {@code branchId} yet, so an unused branch row
 * has no effect on any existing read or write.
 */
@Entity
@Table(name = "branch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Branch {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "code", length = 20)
    private String code;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
