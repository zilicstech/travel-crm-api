package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One page of a hotel search, cheapest first - later pages are read from the same search, not searched again")
public class HotelSearchPageResponse {

    @Schema(description = "Send back with page to get another page of this search; kept 30 minutes")
    private String searchId;

    @Schema(description = "0-based", example = "0")
    private int page;

    @Schema(example = "20")
    private int pageSize;

    @Schema(description = "Hotels in the search that pass the filters sent", example = "143")
    private int total;

    @Schema(description = "This page's hotels")
    private List<HotelOfferResponse> offers;

    @Schema(description = "Names of the suppliers that did not answer this search; their hotels are missing from the list")
    private List<String> suppliersFailed;

    @Schema(description = "Lowest price in the whole search, before filters; null when no hotel has a price", example = "3200.00")
    private BigDecimal priceMin;

    @Schema(description = "Highest price in the whole search, before filters", example = "48000.00")
    private BigDecimal priceMax;

    @Schema(description = "Every meal plan in the whole search, before filters, sorted")
    private List<String> boardBases;

    @Schema(description = "Property types across the whole result, for the filter", example = "[\"Hotel\", \"Resort\"]")
    private List<String> propertyTypes;

    @Schema(description = "Amenities across the whole result, most common first, for the filter", example = "[\"Free WiFi\", \"Swimming Pool\"]")
    private List<String> amenities;
}
