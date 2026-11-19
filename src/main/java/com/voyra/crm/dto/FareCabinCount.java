package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One cabin of the fare type filter and how many flights have a fare in it")
public record FareCabinCount(
        @Schema(example = "ECONOMY") FlightCabin cabin,
        @Schema(description = "Flights of the unfiltered result with at least one fare in this cabin", example = "42") long flights) {
}
