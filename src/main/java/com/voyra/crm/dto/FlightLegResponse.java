package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One direction of a flight offer: one leg for one-way, onward and return offers, two for a COMBO round trip")
public class FlightLegResponse {
    @Schema(example = "IndiGo")
    private String airline;
    @Schema(example = "6E")
    private String airlineCode;
    @Schema(description = "Flight number of the first segment", example = "6E-204")
    private String flightNumber;
    @Schema(example = "DEL")
    private String origin;
    @Schema(example = "BOM")
    private String destination;
    @Schema(example = "2026-11-26")
    private LocalDate departDate;
    @Schema(example = "06:15")
    private LocalTime departTime;
    @Schema(example = "2026-11-26")
    private LocalDate arriveDate;
    @Schema(example = "08:20")
    private LocalTime arriveTime;
    @Schema(description = "As the supplier sent it - minutes (\"140\") or text (\"3h 05m\")", example = "125")
    private String duration;
    @Schema(description = "0 = non-stop", example = "0")
    private int stops;
}
