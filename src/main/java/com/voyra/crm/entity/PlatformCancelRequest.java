package com.voyra.crm.entity;

import com.voyra.crm.enums.CancelReason;
import com.voyra.crm.enums.CancelRequestStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An agency's request to cancel some or all of a platform booking, reviewed and completed by a platform admin (public
 * schema). Passengers are positions in the booking's encrypted passenger list; sectors are {key, label} journeys.
 */
@Entity
@Table(name = "platform_cancel_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformCancelRequest {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "platform_booking_id", nullable = false, length = 36)
    private String platformBookingId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", nullable = false, length = 10)
    private PricingProduct product;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private CancelRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private CancelReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_reason", length = 40)
    private CancelReason finalReason;

    /** integer[], as Lead.kidAges: one Java type must have one mapping (see public V6). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "passengers", columnDefinition = "integer[]", nullable = false)
    private List<Integer> passengers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sectors", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, String>> sectors;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", nullable = false, length = 150)
    private String requestedByName;

    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "supplier_refund", precision = 19, scale = 2)
    private BigDecimal supplierRefund;

    @Column(name = "charges", precision = 19, scale = 2)
    private BigDecimal charges;

    @Column(name = "voyra_fee", precision = 19, scale = 2)
    private BigDecimal voyraFee;

    @Column(name = "refund", precision = 19, scale = 2)
    private BigDecimal refund;

    @Column(name = "recall", precision = 19, scale = 2)
    private BigDecimal recall;

    @Column(name = "exceeds_share", nullable = false)
    private boolean exceedsShare;

    @Column(name = "offline", nullable = false)
    private boolean offline;

    @Column(name = "supplier_reference", length = 100)
    private String supplierReference;

    @Column(name = "admin_note", columnDefinition = "text")
    private String adminNote;

    @Column(name = "completed_by", length = 36)
    private String completedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "personal_data_purged_at")
    private Instant personalDataPurgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
