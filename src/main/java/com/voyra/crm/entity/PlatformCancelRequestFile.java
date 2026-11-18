package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A proof file attached to a cancellation request; {@code fileKey} is null once the file was purged. */
@Entity
@Table(name = "platform_cancel_request_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformCancelRequestFile {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
