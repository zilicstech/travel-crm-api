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

/** Append-only audit of one platform booking's supplier conversation; payloads are redacted before they get here. */
@Entity
@Table(name = "platform_booking_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformBookingEvent {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "platform_booking_id", nullable = false, length = 36)
    private String platformBookingId;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
