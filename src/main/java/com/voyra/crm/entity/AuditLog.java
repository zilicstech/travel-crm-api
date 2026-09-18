package com.voyra.crm.entity;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.enums.AuditAction;
import com.voyra.crm.enums.AuditEntityType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Compliance-grade, entity-agnostic, append-only change record - distinct from
 * {@link LeadTimeline}, which is user-facing narrative scoped to one lead. Neither writes to
 * the other; see {@link com.voyra.crm.service.AuditService} for the full comparison.
 *
 * <p>Never updated and never deleted after insert - see
 * {@link com.voyra.crm.repository.AuditLogRepository}, which does not expose a delete method.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AuditLog {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 36)
    private String entityId;

    @Column(name = "entity_label", length = 200)
    private String entityLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private AuditAction action;

    @Column(name = "actor_id", nullable = false, length = 36)
    private String actorId;

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    /** Empty for CREATE; the full final snapshot for DELETE; the diff for UPDATE. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_changes", columnDefinition = "jsonb")
    @Builder.Default
    private List<AuditChange> fieldChanges = List.of();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
