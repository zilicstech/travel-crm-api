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
 * A stored file belonging to one member - passport scan, visa copy, aadhaar, ticket.
 *
 * <p>{@code fileKey} is an opaque handle returned by FileStorageService, never a filesystem
 * path. That is what lets the storage backend move from local disk to object storage without
 * touching business logic or rewriting rows.
 *
 * <p>Passport <em>number</em> and expiry live on {@link Member} as data, not here. A scan
 * cannot be read by the ticketing or visa flow; the values have to be queryable.
 */
@Entity
@Table(name = "member_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MemberDocument {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "member_id", nullable = false, length = 36)
    private String memberId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "file_key", nullable = false, length = 500)
    private String fileKey;

    @Column(name = "doc_type", length = 50)
    private String docType;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "uploaded_by", length = 36)
    private String uploadedBy;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }
}
