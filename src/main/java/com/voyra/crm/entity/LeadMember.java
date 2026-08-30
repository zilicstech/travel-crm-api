package com.voyra.crm.entity;

import com.voyra.crm.enums.LeadMemberStatus;
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
 * One traveller on one lead - the party manifest row.
 *
 * <p>This is a first-class entity with its own id, not a bare (lead_id, member_id) pair. A
 * pure link table can only say in or out, and removing a row would erase the fact that this
 * traveller's passport was collected and their visa filed before they pulled out. Group
 * members drop constantly; {@code status} plus {@code droppedReason} keeps that history.
 *
 * <p>The document checklist lives here rather than on the lead because one checklist shared
 * by a fourteen-person group tells an agent nothing about who is still missing what.
 *
 * <p>{@code clientId} is carried alongside {@code memberId} so the "does this traveller belong
 * to the lead's client" check is a single-row read, and {@code memberName} is a snapshot kept
 * live-synced when the member is renamed.
 */
@Entity
@Table(name = "lead_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadMember {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    @Column(name = "member_id", nullable = false, length = 36)
    private String memberId;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "member_name", nullable = false, length = 150)
    private String memberName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private LeadMemberStatus status;

    @Column(name = "passport_collected", nullable = false)
    @Builder.Default
    private Boolean passportCollected = false;

    @Column(name = "photos_collected", nullable = false)
    @Builder.Default
    private Boolean photosCollected = false;

    @Column(name = "forms_filled", nullable = false)
    @Builder.Default
    private Boolean formsFilled = false;

    @Column(name = "submitted_to_embassy", nullable = false)
    @Builder.Default
    private Boolean submittedToEmbassy = false;

    @Column(name = "visa_approved", nullable = false)
    @Builder.Default
    private Boolean visaApproved = false;

    @Column(name = "dropped_reason", length = 255)
    private String droppedReason;

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
