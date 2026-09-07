package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for a hotel supplier search")
public class HotelSearchQuery {

    @NotBlank(message = "City is required")
    @Schema(example = "Phuket")
    private String city;

    @NotNull(message = "Check-in date is required")
    @Schema(example = "2026-10-09")
    private LocalDate checkIn;

    @NotNull(message = "Check-out date is required")
    @Schema(example = "2026-10-12")
    private LocalDate checkOut;

    @Min(value = 1, message = "At least one room is required")
    @Schema(example = "1")
    private int rooms = 1;

    @Min(value = 1, message = "At least one adult is required")
    @Schema(example = "2")
    private int adults = 1;

    @Schema(example = "0")
    private int children;
}
