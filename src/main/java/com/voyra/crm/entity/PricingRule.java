package com.voyra.crm.entity;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
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

/**
 * A named discount or commission percentage for one product, shared by every agency assigned to it - public schema.
 * Editing it changes the price for all of them from their next review; {@code version} moves on each such edit so a
 * booking reviewed before the edit is re-priced at confirm.
 */
@Entity
@Table(name = "pricing_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PricingRule {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private PricingRuleKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", nullable = false, length = 10)
    private PricingProduct product;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    /** Commission rules only: the agency's commission may exceed Voyra's estimated earning. */
    @Column(name = "allow_above_earned", nullable = false)
    private boolean allowAboveEarned;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
