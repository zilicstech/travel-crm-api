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
import java.util.List;
import java.util.Map;

/** One customer e-ticket download of a platform flight - public schema. Holds the markup and the printed fare lines frozen at download; no personal data. */
@Entity
@Table(name = "platform_booking_ticket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformBookingTicket {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "platform_booking_id", nullable = false, length = 36)
    private String platformBookingId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    /** 1, 2, 3... per booking, assigned under the booking row lock. */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "markup", nullable = false, precision = 19, scale = 2)
    private BigDecimal markup;

    /** Printed fare lines {label, amount (plain decimal string)}, frozen at download. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fare_lines", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> fareLines;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 160)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
