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
@Schema(description = "One page of grouped flights plus the facets the filter panel needs")
public class FlightSearchPageResponse {

    private List<FlightGroupResponse> flights;
    private int page;
    private int pageSize;

    @Schema(description = "Flights matching the filters, across all pages")
    private int totalFlights;

    private int totalPages;

    @Schema(description = "Every airline in the unfiltered result, so the filter list does not shrink as you filter")
    private List<String> airlines;

    @Schema(description = "Key of the cheapest flight in the filtered set (null when empty)")
    private String cheapestKey;

    @Schema(description = "Key of the fastest flight in the filtered set (null when empty)")
    private String fastestKey;
}
