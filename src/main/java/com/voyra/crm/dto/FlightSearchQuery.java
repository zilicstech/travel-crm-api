package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for a flight supplier search")
public class FlightSearchQuery {

    @NotBlank(message = "Origin is required")
    @Schema(description = "IATA airport code, picked via the airport picker", example = "BLR")
    private String origin;

    @NotBlank(message = "Destination is required")
    @Schema(description = "IATA airport code, picked via the airport picker", example = "HKT")
    private String destination;

    @NotNull(message = "Departure date is required")
    @FutureOrPresent(message = "Departure date can't be in the past")
    @Schema(example = "2026-10-09")
    private LocalDate departDate;

    @Schema(description = "Omit for a one-way search; on or after departDate", example = "2026-10-25")
    private LocalDate returnDate;

    @JsonIgnore
    @AssertTrue(message = "Return can't be before departure")
    public boolean isReturnOnOrAfterDeparture() {
        return returnDate == null || departDate == null || !returnDate.isBefore(departDate);
    }

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
