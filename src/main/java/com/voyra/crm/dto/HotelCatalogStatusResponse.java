package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "One supplier's hotel catalogue for name search: whether a load runs, how many hotels it holds")
public record HotelCatalogStatusResponse(
        String supplierId,
        String supplierName,
        @Schema(description = "RUNNING, DONE or FAILED (the last load's outcome)", example = "DONE") String status,
        @Schema(description = "Hotels held now (grows while a load runs)", example = "48210") long hotels,
        Instant startedAt,
        Instant finishedAt,
        @Schema(description = "When a load last completed; null when none has") Instant lastLoadedAt,
        @Schema(description = "Why the last load failed; its hotels were kept") String lastError) {
}
