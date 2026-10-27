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

import java.time.Instant;
import java.util.Map;

/** A verified webhook whose order we do not know (yet); retried on redelivery, alerted after repeats. */
@Entity
@Table(name = "gateway_webhook_orphan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class GatewayWebhookOrphan {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "gateway_order_id", nullable = false, length = 60)
    private String gatewayOrderId;

    @Column(name = "event", nullable = false, length = 60)
    private String event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
