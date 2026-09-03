package com.voyra.crm.entity;

import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One system-recorded event in a lead's history.
 *
 * <p>Append-only and written only by the service layer on a state change. There is
 * deliberately no create endpoint: the moment a client can post arbitrary timeline rows the
 * stream stops being an audit trail. Nothing updates or deletes these rows either.
 *
 * <p>{@code fromStatus} and {@code toStatus} are populated only for STATUS_CHANGED events;
 * every other event type leaves them null and carries its detail in {@code description}.
 */
@Entity
@Table(name = "lead_timeline")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadTimeline {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    /** Null means a lead-level event; set means this row is about one service instance. */
    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private LeadTimelineEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private LeadStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20)
    private LeadStatus toStatus;

    @Column(name = "actor_agent_id", nullable = false, length = 36)
    private String actorAgentId;

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
