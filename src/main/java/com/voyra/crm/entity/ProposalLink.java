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
 * Public-schema index resolving an unauthenticated proposal share-link token to the tenant
 * schema + lead that own it. Deliberately keyed on a high-entropy token, never the lead's
 * real (short, enumerable) id - see the public proposal flow design.
 */
@Entity
@Table(name = "proposal_link")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProposalLink {

    @Id
    @Column(name = "token", length = 32)
    private String token;

    @Column(name = "tenant_id", nullable = false, length = 6)
    private String tenantId;

    @Column(name = "lead_id", nullable = false, length = 6)
    private String leadId;

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
