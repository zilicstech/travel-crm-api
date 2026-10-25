package com.voyra.crm.entity;

import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/** One agency's CRM plan - public schema, exactly one row per tenant. Every change refreshes TenantCache. */
@Entity
@Table(name = "subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Subscription {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 8)
    private SubscriptionPlan plan;

    /** Sku names; read only for CUSTOM (SUITE is every SKU, STARTER none). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skus", nullable = false, columnDefinition = "text[]")
    private List<String> skus;

    /** SUITE: extra seats on top of the 5 included; CUSTOM: all seats. */
    @Column(name = "seats", nullable = false)
    private Integer seats;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SubscriptionStatus status;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "waived_reason", length = 16)
    private WaivedReason waivedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 8)
    private BillingCycle billingCycle;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
