package com.voyra.crm.entity;

import com.voyra.crm.enums.ClientType;
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
 * The commercial entity the agency deals with - a B2C household or a B2B group/company.
 *
 * <p>A client is not a person. People are {@link Member} rows, including the client's own
 * primary member. That separation is what lets one human appear on a family's roster and also
 * hold their own client account without their passport being stored twice.
 *
 * <p>{@code identifier} carries a partial unique index scoped to live rows, so re-creating a
 * client that already exists surfaces as a 409 rather than silently duplicating the account.
 * {@code agentName} is a denormalized snapshot re-synced in bulk by AgentService whenever the
 * owning agent is renamed.
 */
@Entity
@Table(name = "client")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Client {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "identifier", nullable = false, length = 150)
    private String identifier;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private ClientType type;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    @Column(name = "agent_name", nullable = false, length = 150)
    private String agentName;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
