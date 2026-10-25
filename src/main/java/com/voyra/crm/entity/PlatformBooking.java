package com.voyra.crm.entity;

import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.VoyraEarnedSource;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A booking made through Voyra's supplier account - public schema. Owns the supplier and money
 * state; the agency's CRM booking (if any) is only a mirror pointed at by {@code tenantBookingId}.
 */
@Entity
@Table(name = "platform_booking")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformBooking {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", nullable = false, length = 8)
    private PricingProduct product;

    @Column(name = "client_id", length = 36)
    private String clientId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "issued_by_agent_id", length = 36)
    private String issuedByAgentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlatformBookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 4)
    private SupplierEnvironment environment;

    @Column(name = "credential_id", length = 36)
    private String credentialId;

    @Column(name = "supplier_review_id", length = 80)
    private String supplierReviewId;

    @Column(name = "supplier_booking_id", length = 80)
    private String supplierBookingId;

    @Column(name = "pnr", length = 40)
    private String pnr;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "ticket_numbers", columnDefinition = "text[]")
    private List<String> ticketNumbers;

    @Column(name = "gross", precision = 19, scale = 2)
    private BigDecimal gross;

    @Column(name = "base", precision = 19, scale = 2)
    private BigDecimal base;

    @Column(name = "discount_pct", precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(name = "displayed", precision = 19, scale = 2)
    private BigDecimal displayed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pricing_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> pricingSnapshot;

    @Column(name = "voyra_earned", precision = 19, scale = 2)
    private BigDecimal voyraEarned;

    @Enumerated(EnumType.STRING)
    @Column(name = "voyra_earned_source", length = 10)
    private VoyraEarnedSource voyraEarnedSource;

    @Column(name = "voyra_earned_actual", precision = 19, scale = 2)
    private BigDecimal voyraEarnedActual;

    @Column(name = "voyra_earned_reversal", precision = 19, scale = 2)
    private BigDecimal voyraEarnedReversal;

    @Column(name = "agency_commission", precision = 19, scale = 2)
    private BigDecimal agencyCommission;

    @Column(name = "tds", precision = 19, scale = 2)
    private BigDecimal tds;

    @Column(name = "voyra_net", precision = 19, scale = 2)
    private BigDecimal voyraNet;

    @Column(name = "pax_count")
    private Integer paxCount;

    /** AES-encrypted passenger JSON (PasswordEncryptionUtil); never logged or put in an event. */
    @Column(name = "pax_enc", columnDefinition = "text")
    private String paxEnc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "itinerary", columnDefinition = "jsonb")
    private Map<String, Object> itinerary;

    @Column(name = "hold_entry_id", length = 36)
    private String holdEntryId;

    @Column(name = "debit_entry_id", length = 36)
    private String debitEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mirror_status", nullable = false, length = 8)
    @Builder.Default
    private MirrorStatus mirrorStatus = MirrorStatus.NONE;

    @Column(name = "tenant_booking_id", length = 36)
    private String tenantBookingId;

    @Column(name = "booked_by", length = 36)
    private String bookedBy;

    @Column(name = "abort_reason", length = 300)
    private String abortReason;

    @Column(name = "supplier_message", length = 500)
    private String supplierMessage;

    @Column(name = "fare_rules", columnDefinition = "text")
    private String fareRules;

    @Column(name = "review_expires_at")
    private Instant reviewExpiresAt;

    @Column(name = "cancel_refund", precision = 19, scale = 2)
    private BigDecimal cancelRefund;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_count", nullable = false)
    @Builder.Default
    private Integer pollCount = 0;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ticketed_at")
    private Instant ticketedAt;
}
