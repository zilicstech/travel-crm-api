package com.voyra.crm.entity;

import com.voyra.crm.enums.BankStatementImportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One upload-and-parse run. {@link #storageKey} is opaque, resolved through FileStorageService, never the raw file itself. */
@Entity
@Table(name = "bank_statement_import")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankStatementImport {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "bank_account_id", nullable = false, length = 36)
    private String bankAccountId;

    @Column(name = "profile_id", nullable = false, length = 36)
    private String profileId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "row_count", nullable = false)
    @Builder.Default
    private Integer rowCount = 0;

    @Column(name = "duplicate_count", nullable = false)
    @Builder.Default
    private Integer duplicateCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BankStatementImportStatus status;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "imported_by", length = 36)
    private String importedBy;
}
