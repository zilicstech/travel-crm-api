package com.voyra.crm.entity;

import com.voyra.crm.enums.CommunicationChannel;
import com.voyra.crm.enums.CommunicationDirection;
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
 * A manually-logged call, WhatsApp thread, email or meeting - the internal answer to
 * "communication history" without a real WhatsApp/email API integration (out of scope this
 * phase - no external credentials). Scoped to the client, since a conversation is often about
 * the relationship generally; {@code leadId} is set only when it was about one specific enquiry.
 * {@code fileKey} is opaque, from {@link com.voyra.crm.service.FileStorageService}, never a path.
 */
@Entity
@Table(name = "communication_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CommunicationLog {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "member_id", length = 36)
    private String memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommunicationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private CommunicationDirection direction;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "summary", nullable = false, length = 2000)
    private String summary;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "outcome", length = 200)
    private String outcome;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "actor_id", nullable = false, length = 36)
    private String actorId;

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
