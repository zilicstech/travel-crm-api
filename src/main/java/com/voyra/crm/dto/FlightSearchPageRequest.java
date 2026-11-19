package com.voyra.crm.dto;

import com.voyra.crm.enums.BaggageFilter;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightRefundType;
import com.voyra.crm.enums.FlightSortOrder;
import com.voyra.crm.enums.TimeBucket;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "A flight search plus how to page, sort and filter its result")
public class FlightSearchPageRequest {

    @Valid
    @NotNull(message = "Search query is required")
    @Schema(description = "The search itself - route, dates, passengers, cabin")
    private FlightSearchQuery query;

    @Min(value = 1, message = "Page starts at 1")
    @Schema(example = "1")
    private int page = 1;

    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 50, message = "Page size must be at most 50")
    @Schema(example = "20")
    private int pageSize = 20;

    @NotNull(message = "Sort order is required")
    @Schema(description = "Result order")
    private FlightSortOrder sort = FlightSortOrder.PRICE;

    @Min(value = 0, message = "Stops cannot be negative")
    @Max(value = 3, message = "Stops can be at most 3")
    @Schema(description = "0 = non-stop only, 1 = exactly one stop, 2 = exactly two, 3 = three or more; omit for any", example = "0")
    private Integer stops;

    @Schema(description = "Only these airlines (by name or IATA code); omit or empty for all")
    private List<String> airlines;

    @Schema(description = "Only fares of these suppliers (supplier ids); omit or empty for all")
    private List<String> suppliers;

    @Schema(description = "Only refundable fares")
    private boolean refundableOnly;

    // The filters below narrow the cached result; none of them re-asks the supplier.
    @DecimalMin(value = "0", message = "Minimum price can't be negative")
    @Schema(description = "Lowest fare to show, against the fare the agency is shown for the whole party", example = "4000")
    private BigDecimal minPrice;

    @DecimalMin(value = "0", message = "Maximum price can't be negative")
    @Schema(description = "Highest fare to show", example = "9000")
    private BigDecimal maxPrice;

    @Schema(description = "Only flights leaving in one of these windows (EARLY 00-06, MORNING 06-12, AFTERNOON 12-18, EVENING 18-24)")
    private List<TimeBucket> departBuckets;

    @Schema(description = "Only flights arriving in one of these windows")
    private List<TimeBucket> arriveBuckets;

    @Schema(description = "Only fares of these fare types, ignoring case", example = "[\"PUBLISHED\"]")
    private List<@NotBlank(message = "Fare type can't be blank") String> fareTypes;

    @Schema(description = "Only fares in one of these cabins (the grouped fare type filter)", example = "[\"ECONOMY\"]")
    private List<FlightCabin> fareCabins;

    @Schema(description = "Only fares with one of these refund types")
    private List<FlightRefundType> refundTypes;

    @Size(max = 10, message = "Flight number can be at most 10 characters")
    @Schema(description = "Only flights whose number contains this, with or without the airline code", example = "SG671")
    private String flightNumber;

    @Schema(description = "Only flights leaving from these airports (codes)", example = "[\"DEL\"]")
    private List<String> origins;

    @Schema(description = "Only flights arriving at these airports (codes)", example = "[\"BOM\"]")
    private List<String> destinations;

    @Schema(description = "CHECK_IN = only fares with a check-in allowance, HAND_ONLY = only fares without one")
    private BaggageFilter baggage;

    @Schema(description = "True on a fresh Search click: re-queries the supplier instead of reusing "
            + "the cached result. False for page, sort and filter changes.")
    private boolean refresh;
}
