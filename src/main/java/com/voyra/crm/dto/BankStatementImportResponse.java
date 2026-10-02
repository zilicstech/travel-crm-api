package com.voyra.crm.dto;

import com.voyra.crm.enums.BankStatementImportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class BankStatementImportResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bankAccountId;

    @Schema(example = "hdfc-sept-2026.csv")
    private String fileName;

    @Schema(example = "42")
    private Integer rowCount;

    @Schema(example = "1")
    private Integer duplicateCount;

    @Schema(example = "18")
    private Integer tier1Matched;

    @Schema(example = "COMPLETED")
    private BankStatementImportStatus status;

    @Schema(description = "Rows the parser could not read - never silently dropped", example = "[]")
    private java.util.List<String> parseErrors;

    @Schema(example = "2026-10-02T11:00:00")
    private LocalDateTime importedAt;
}
