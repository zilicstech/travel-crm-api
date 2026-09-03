package com.voyra.crm.entity;

import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.ServiceType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A promise to chase something on a lead, scoped to a service TYPE (nullable = trip-level),
 * never to one service instance - "chase the embassy Monday" is Visa work whichever visa it is
 * about. Completed, never deleted from the client's point of view (the DELETE endpoint exists
 * for genuine entry mistakes only); what was promised and whether it was honoured is the record.
 *
 * <p>Every insert/complete/delete recomputes {@code lead.open_follow_ups} and
 * {@code lead.follow_up_date} (the earliest OPEN due_date) in the same transaction - that field
 * is a derived cache now, not something a client writes directly.
 */
@Entity
@Table(name = "lead_follow_up")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadFollowUp {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "lead_destination", nullable = false, length = 150)
    private String leadDestination;

    /** Null means trip-level. */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 20)
    private ServiceType serviceType;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "note", nullable = false, length = 500)
    private String note;

    @Column(name = "assigned_agent_id", nullable = false, length = 36)
    private String assignedAgentId;

    @Column(name = "assigned_agent_name", nullable = false, length = 150)
    private String assignedAgentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private FollowUpStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
