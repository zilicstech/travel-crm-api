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
@Schema(description = "Interactive seat map for a reviewed flight booking")
public class FlightSeatMapResponse {

    @Schema(description = "Platform booking id", example = "b1d2e3f4-0000-0000-0000-000000000001")
    private String bookingId;

    @Schema(description = "True when every adult and child must pick a seat on each available segment before confirming", example = "true")
    private boolean seatMandatory;

    @Schema(description = "Seat maps in flight segment order")
    private List<SegmentSeatMapResponse> segments;
}
