package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "One itinerary leg under a booking passenger - only meaningful for a sector-wise service category (air, rail).")
public class BookingSectorRequest {

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
