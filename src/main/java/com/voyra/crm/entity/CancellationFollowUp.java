package com.voyra.crm.entity;

import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.FollowUpStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A follow-up on a booking's cancellation, or a cancellation scheduled for later. Completed, never deleted. */
@Entity
@Table(name = "cancellation_follow_up")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CancellationFollowUp {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CancellationFollowUpKind kind;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "assigned_agent_id", nullable = false, length = 36)
    private String assignedAgentId;

    @Column(name = "assigned_agent_name", nullable = false, length = 150)
    private String assignedAgentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private FollowUpStatus status;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_by_name", nullable = false, length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "done_by", length = 36)
    private String doneBy;

    @Column(name = "done_by_name", length = 150)
    private String doneByName;

    @Column(name = "done_at")
    private LocalDateTime doneAt;
}
