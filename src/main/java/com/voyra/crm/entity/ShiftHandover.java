package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A note per shift, not rostering/clock-in: an outgoing agent (or the owner) leaves a
 * summary plus a pinned lead/booking id list for whoever picks up next. A null
 * {@code toAgentId} means the whole team.
 */
@Entity
@Table(name = "shift_handover")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShiftHandover {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "from_agent_id", nullable = false, length = 36)
    private String fromAgentId;

    @Column(name = "from_agent_name", nullable = false, length = 150)
    private String fromAgentName;

    /** Null means the whole team. */
    @Column(name = "to_agent_id", length = 36)
    private String toAgentId;

    @Column(name = "to_agent_name", length = 150)
    private String toAgentName;

    @Column(name = "summary", nullable = false, length = 2000)
    private String summary;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pinned_lead_ids", columnDefinition = "text[]")
    @Builder.Default
    private List<String> pinnedLeadIds = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pinned_booking_ids", columnDefinition = "text[]")
    @Builder.Default
    private List<String> pinnedBookingIds = List.of();

    @Column(name = "shift_ended_at", nullable = false)
    private LocalDateTime shiftEndedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 36)
    private String acknowledgedBy;

    @Column(name = "acknowledged_by_name", length = 150)
    private String acknowledgedByName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (shiftEndedAt == null) {
            shiftEndedAt = LocalDateTime.now();
        }
    }
}
