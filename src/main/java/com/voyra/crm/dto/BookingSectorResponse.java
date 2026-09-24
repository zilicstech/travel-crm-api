package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
@Schema(description = "One itinerary leg under a booking passenger.")
public class BookingSectorResponse {

    @Schema(example = "8f2a1c3d-...")
    private String id;

    @Schema(example = "DLA")
    private String sectorFrom;

    @Schema(example = "ADD")
    private String sectorTo;

    @Schema(example = "ET 962")
    private String flightNumber;

    @Schema(example = "2026-10-31")
    private LocalDate travelDate;

    @Schema(example = "ECO")
    private String cabinClass;
}
