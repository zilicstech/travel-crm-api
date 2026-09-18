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
 * Public-schema index resolving an unauthenticated feedback share-link token to the tenant
 * schema + booking that own it. Same shape and reasoning as {@link ProposalLink} - keyed on
 * a high-entropy token, never the booking's real (short, enumerable) id.
 */
@Entity
@Table(name = "feedback_link")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class FeedbackLink {

    @Id
    @Column(name = "token", length = 32)
    private String token;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
