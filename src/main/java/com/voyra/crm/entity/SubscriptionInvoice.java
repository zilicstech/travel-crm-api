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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Voyra's invoice to an agency for its subscription - public schema. Issued and settled by SubscriptionBillingService. */
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

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** Frozen at issue: sku, description, quantity, unitPrice (monthly, string), months, amount (string). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> lines = new ArrayList<>();

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "gst", nullable = false, precision = 19, scale = 2)
    private BigDecimal gst;

    /** CGST 9 % + SGST 9 % when the agency is in Voyra's state, else IGST 18 %; gst = their sum. */
    @Column(name = "cgst", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cgst = BigDecimal.ZERO;

    @Column(name = "sgst", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sgst = BigDecimal.ZERO;

    @Column(name = "igst", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal igst = BigDecimal.ZERO;

    @Column(name = "sac_code", length = 8)
    private String sacCode;

    /** Frozen at issue: {seller: {legalName, gstin, address, stateCode}, buyer: {name, gstin, address, stateCode}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_details", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> billingDetails = new HashMap<>();

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    /** DRAFT, ISSUED, PAID or VOID. */
    @Column(name = "status", nullable = false, length = 6)
    private String status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "gateway_order_id", length = 60)
    private String gatewayOrderId;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** GATEWAY or BANK. */
    @Column(name = "paid_via", length = 10)
    private String paidVia;

    /** Gateway payment id or bank reference. */
    @Column(name = "paid_ref", length = 100)
    private String paidRef;

    @Column(name = "pdf_key", length = 500)
    private String pdfKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
