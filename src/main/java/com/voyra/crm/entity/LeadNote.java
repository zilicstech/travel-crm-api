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

import java.time.LocalDateTime;

/**
 * An agent-authored note on a lead - what was said on the call.
 *
 * <p>Distinct from {@link LeadTimeline}, which the service writes automatically on state
 * changes. Notes are human prose and can say anything; timeline rows are structured events
 * and have no write endpoint. Both are needed: one is the conversation, the other is the
 * audit trail.
 *
 * <p>{@code authorName} is a snapshot re-synced in bulk when the agent is renamed, so an old
 * note never shows a stale name.
 */
@Entity
@Table(name = "lead_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadNote {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    @Column(name = "author_agent_id", nullable = false, length = 36)
    private String authorAgentId;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
