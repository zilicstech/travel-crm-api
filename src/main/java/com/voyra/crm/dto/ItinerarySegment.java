package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "One flight leg of the booking, trimmed to what an agent needs to read")
public class ItinerarySegment {

    @Schema(description = "Departure airport code", example = "DEL")
    private String from;

    @Schema(description = "Arrival airport code", example = "BOM")
    private String to;

    @Schema(description = "Departure local time as the supplier reports it", example = "2026-12-01T06:00")
    private String departure;

    @Schema(description = "Arrival local time as the supplier reports it", example = "2026-12-01T08:10")
    private String arrival;

    @Schema(description = "Airline name", example = "IndiGo")
    private String airline;

    @Schema(description = "Airline code and flight number", example = "6E-201")
    private String flightNumber;
}
