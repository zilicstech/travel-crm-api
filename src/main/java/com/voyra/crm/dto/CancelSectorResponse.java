package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One journey of a flight booking that can be cancelled")
public record CancelSectorResponse(
        @Schema(description = "Stable key within the booking: d0 onward, d1 return", example = "d0") String key,
        @Schema(description = "From and to", example = "DEL-BOM") String label,
        @Schema(description = "Departure date (yyyy-MM-dd) when known", example = "2026-12-01") String date) {
}
