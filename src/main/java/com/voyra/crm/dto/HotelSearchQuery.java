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

    @Schema(description = "ISO2 country of the city, to tell apart two cities with the same name", example = "TH")
    private String countryCode;

    @Schema(description = "Age of each child at check-in; a missing age is sent to the supplier as 8", example = "[6, 9]")
    private java.util.List<Integer> childAges;

    @Schema(description = "Opening a hotel from a search: the search's correlationId, which the supplier ties its rates to")
    private String correlationId;

    @Schema(description = "A later page of an earlier search: its searchId. Blank runs a new search")
    private String searchId;

    @jakarta.validation.constraints.Min(value = 0, message = "Page can't be negative")
    @Schema(description = "0-based page of 20 hotels, cheapest first", example = "0")
    private int page;

    // The filters below narrow the kept search on every page request; none of them re-asks the supplier.
    @jakarta.validation.constraints.Size(max = 100, message = "Hotel name can be at most 100 characters")
    @Schema(description = "Only hotels whose name contains this, ignoring case", example = "amari")
    private String name;

    @Schema(description = "Only hotels with one of these star ratings", example = "[4, 5]")
    private java.util.List<@NotNull(message = "Star rating must be 1 to 5") @Min(value = 1, message = "Star rating must be 1 to 5")
            @jakarta.validation.constraints.Max(value = 5, message = "Star rating must be 1 to 5") Integer> stars;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "Minimum price can't be negative")
    @Schema(description = "Lowest price to show, against the amount the list is sorted by", example = "5000")
    private java.math.BigDecimal minPrice;

    @jakarta.validation.constraints.DecimalMin(value = "0", message = "Maximum price can't be negative")
    @Schema(description = "Highest price to show", example = "20000")
    private java.math.BigDecimal maxPrice;

    @Schema(description = "True shows only refundable offers", example = "true")
    private Boolean refundableOnly;

    @Schema(description = "Only offers with one of these meal plans, ignoring case", example = "[\"Breakfast Included\"]")
    private java.util.List<@NotBlank(message = "Meal plan can't be blank") String> boardBasis;

    @Schema(description = "PRICE (default, cheapest first) or STARS (highest rated first, then cheapest)", example = "PRICE")
    private com.voyra.crm.enums.HotelSortOrder sort;
}
