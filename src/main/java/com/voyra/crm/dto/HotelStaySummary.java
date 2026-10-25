package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "The stay booked on a hotel booking")
public record HotelStaySummary(
        @Schema(description = "Hotel name", example = "Sea Breeze Resort") String hotelName,
        @Schema(description = "City", example = "Goa") String city,
        @Schema(description = "Check-in date", example = "2026-12-01") LocalDate checkIn,
        @Schema(description = "Check-out date", example = "2026-12-03") LocalDate checkOut,
        @Schema(description = "Nights", example = "2") Integer nights,
        @Schema(description = "Rooms booked", example = "1") Integer rooms,
        @Schema(description = "Meal basis", example = "Breakfast included") String mealBasis) {
}
