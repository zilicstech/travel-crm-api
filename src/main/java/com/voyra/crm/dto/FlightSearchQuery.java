package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for a flight supplier search")
public class FlightSearchQuery {

    @NotBlank(message = "Origin is required")
    @Schema(example = "Bangalore")
    private String origin;

    @NotBlank(message = "Destination is required")
    @Schema(example = "Phuket")
    private String destination;

    @NotNull(message = "Departure date is required")
    @Schema(example = "2026-10-09")
    private LocalDate departDate;

    @Schema(description = "Omit for a one-way search", example = "2026-10-25")
    private LocalDate returnDate;

    @Schema(example = "ECONOMY")
    private FlightCabin cabin;

    @Min(value = 1, message = "At least one adult is required")
    @Schema(example = "2")
    private int adults = 1;

    @Schema(example = "0")
    private int children;

    @Schema(example = "0")
    private int infants;
}
