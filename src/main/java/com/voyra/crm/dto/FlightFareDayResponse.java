package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "One calendar day: the cheapest fare per adult an earlier search found, after the caller agency's discount")
public record FlightFareDayResponse(
        @Schema(example = "2026-12-01") LocalDate date,
        @Schema(description = "Per adult, indicative: the fare is confirmed by a search", example = "4484.00") BigDecimal fare) {
}
