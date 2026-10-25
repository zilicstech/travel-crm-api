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

/** One Tripjack statement file imported by a platform admin - public schema. The SHA-256 is unique: a file imports once. */
@Entity
@Table(name = "platform_statement_import")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformStatementImport {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "total_lines", nullable = false)
    private Integer totalLines;

    @Column(name = "applied_lines", nullable = false)
    private Integer appliedLines;

    @Column(name = "duplicate_lines", nullable = false)
    private Integer duplicateLines;

    @Column(name = "unmatched_lines", nullable = false)
    private Integer unmatchedLines;

    @Column(name = "imported_by", nullable = false, length = 36)
    private String importedBy;

    @Column(name = "imported_by_name", length = 150)
    private String importedByName;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;
}
