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
@Schema(description = "One page of a hotel search, cheapest first - later pages are read from the same search, not searched again")
public class HotelSearchPageResponse {

    @Schema(description = "Send back with page to get another page of this search; kept 30 minutes")
    private String searchId;

    @Schema(description = "0-based", example = "0")
    private int page;

    @Schema(example = "20")
    private int pageSize;

    @Schema(description = "Hotels in the whole search", example = "143")
    private int total;

    @Schema(description = "This page's hotels")
    private List<HotelOfferResponse> offers;
}
