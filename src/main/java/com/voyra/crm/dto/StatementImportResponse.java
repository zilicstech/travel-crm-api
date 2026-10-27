package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a Tripjack statement import")
public class StatementImportResponse {

    @Schema(description = "Import id", example = "i1d2e3f4-0000-0000-0000-000000000001")
    private String id;

    @Schema(description = "Uploaded file name", example = "tripjack-2026-10.csv")
    private String fileName;

    @Schema(description = "When it was imported")
    private Instant importedAt;

    @Schema(description = "Platform admin who imported it", example = "root@voyra.in")
    private String importedByName;

    @Schema(description = "Data lines in the file", example = "120")
    private int totalLines;

    @Schema(description = "Lines applied to a booking", example = "117")
    private int appliedLines;

    @Schema(description = "Lines already applied by an earlier line or import", example = "1")
    private int duplicateLines;

    @Schema(description = "Lines that matched no booking they could apply to", example = "2")
    private int unmatchedLines;

    @Schema(description = "Every line with its outcome, in file order")
    private List<StatementLineResponse> lines;
}
