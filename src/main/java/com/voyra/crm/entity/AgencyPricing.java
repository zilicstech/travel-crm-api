package com.voyra.crm.entity;

import com.voyra.crm.enums.PricingProduct;
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
 * One agency's discount and commission rate for a product from {@code effectiveFrom} on - public
 * schema. Rows are appended, never edited, except the approval columns.
 */
@Entity
@Table(name = "agency_pricing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AgencyPricing {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", nullable = false, length = 10)
    private PricingProduct product;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(name = "commission_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPct;

    @Column(name = "allow_commission_above_earned", nullable = false)
    private boolean allowCommissionAboveEarned;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "worst_case_net_per_10k", nullable = false, precision = 19, scale = 2)
    private BigDecimal worstCaseNetPer10k;

    @Column(name = "requires_second_approval", nullable = false)
    private boolean requiresSecondApproval;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Set when a never-approved rate is withdrawn; the row stays for the record and never applies. */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by", length = 36)
    private String withdrawnBy;
}
