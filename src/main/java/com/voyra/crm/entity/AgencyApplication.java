package com.voyra.crm.entity;

import com.voyra.crm.enums.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An agency's public application to join the platform - public schema; becomes a Tenant on approval. */
@Entity
@Table(name = "agency_application")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AgencyApplication {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 14)
    private ApplicationStatus status;

    @Column(name = "agency_name", nullable = false, length = 150)
    private String agencyName;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Column(name = "pan", nullable = false, length = 10)
    private String pan;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "cin", length = 30)
    private String cin;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state_code", length = 2)
    private String stateCode;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "owner_name", nullable = false, length = 150)
    private String ownerName;

    @Column(name = "owner_email", nullable = false, length = 150)
    private String ownerEmail;

    @Column(name = "owner_phone", nullable = false, length = 30)
    private String ownerPhone;

    @Column(name = "expected_monthly_volume", length = 40)
    private String expectedMonthlyVolume;

    @Column(name = "base_currency_code", nullable = false, length = 3)
    private String baseCurrencyCode;

    @Column(name = "consent_at")
    private Instant consentAt;

    /** SHA-256 hex of the upload token; the raw token is shown to the applicant once and never stored. */
    @Column(name = "upload_token_hash", nullable = false, length = 64)
    private String uploadTokenHash;

    @Column(name = "upload_token_expires_at", nullable = false)
    private Instant uploadTokenExpiresAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
