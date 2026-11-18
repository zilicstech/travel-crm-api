package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aircraft cabin seat map for one flight segment")
public class SegmentSeatMapResponse {

    @Schema(description = "Segment key matching PaxSeatSelection.segmentKey", example = "1490")
    private String segmentKey;

    @Schema(description = "Operating or marketing airline IATA code", example = "6E")
    private String airlineCode;

    @Schema(description = "Flight number", example = "6259")
    private String flightNumber;

    @Schema(description = "Origin airport IATA code", example = "BHO")
    private String origin;

    @Schema(description = "Destination airport IATA code", example = "DEL")
    private String destination;

    @Schema(description = "Cabin grid row count", example = "30")
    private int rows;

    @Schema(description = "Cabin grid column count (missing column indices represent aisles)", example = "7")
    private int columns;

    @Schema(description = "Airline note when seat map has special remarks", example = "Exit row seats require eligible adults")
    private String note;

    @Schema(description = "All seats in this segment's cabin grid")
    private List<SeatOptionResponse> seats;
}
