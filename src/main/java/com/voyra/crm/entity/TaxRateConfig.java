package com.voyra.crm.entity;

import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
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
 * One GST or TCS slab, effective-dated. Never updated in place once issued invoices may
 * reference it by id for provenance - a rate change closes this row ({@link #effectiveTo}) and
 * inserts a new one, which is what lets a historical invoice be reproduced exactly (see
 * {@code TaxEngine}). {@code sacCode} is null for TCS rows.
 */
@Entity
@Table(name = "tax_rate_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TaxRateConfig {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_kind", nullable = false, length = 20)
    private TaxKind taxKind;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    @Column(name = "sac_code", length = 10)
    private String sacCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "supply_nature", nullable = false, length = 30)
    private SupplyNature supplyNature;

    @Column(name = "rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal ratePercent = BigDecimal.ZERO;

    @Column(name = "taxable_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal taxablePercent = new BigDecimal("100.000");

    @Column(name = "threshold_amount", precision = 19, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "tcs_section", length = 20)
    private String tcsSection;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
