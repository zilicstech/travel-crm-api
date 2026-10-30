package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One page of grouped flights plus the facets the filter panel needs")
public class FlightSearchPageResponse {

    @Schema(description = "This page of flights")
    private List<FlightGroupResponse> flights;
    @Schema(description = "ONE_WAY, ROUND_TRIP_SPLIT (flights are onward, returnFlights the return) or ROUND_TRIP_COMBO (each flight covers both ways)")
    private com.voyra.crm.enums.FlightSearchTripType tripType;

    @Schema(description = "Return flights of a ROUND_TRIP_SPLIT search, filtered and sorted like flights but not paged; empty otherwise")
    private List<FlightGroupResponse> returnFlights;

    @Schema(description = "1-based page number")
    private int page;
    @Schema(description = "Flights per page")
    private int pageSize;

    @Schema(description = "Flights matching the filters, across all pages")
    private int totalFlights;

    @Schema(description = "Pages available for the current filters")
    private int totalPages;

    @Schema(description = "Every airline in the unfiltered result, so the filter list does not shrink as you filter")
    private List<String> airlines;

    @Schema(description = "Airline name to IATA code for every airline in the unfiltered result, so the filter list can show logos")
    private Map<String, String> airlineCodes;

    @Schema(description = "Key of the cheapest flight in the filtered set (null when empty)")
    private String cheapestKey;

    @Schema(description = "Key of the fastest flight in the filtered set (null when empty)")
    private String fastestKey;
}
