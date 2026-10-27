package com.voyra.crm.entity;

import com.voyra.crm.enums.PricingProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** Which discount and commission rule an agency is on for a product - public schema. No row means the Default rules. */
@Entity
@Table(name = "agency_pricing_assignment")
@IdClass(AgencyPricingAssignment.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AgencyPricingAssignment {

    @Id
    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "product", length = 10)
    private PricingProduct product;

    @Column(name = "discount_rule_id", nullable = false, length = 36)
    private String discountRuleId;

    @Column(name = "commission_rule_id", nullable = false, length = 36)
    private String commissionRuleId;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String tenantId;
        private PricingProduct product;
    }
}
