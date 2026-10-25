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
import java.time.Instant;

/** Voyra's invoice to an agency for its subscription - public schema. Task 4 issues and settles these. */
@Entity
@Table(name = "subscription_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SubscriptionInvoice {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "number", length = 30)
    private String number;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "gst")
    private BigDecimal gst;

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    /** DRAFT, ISSUED, PAID or VOID. */
    @Column(name = "status", nullable = false, length = 6)
    private String status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
