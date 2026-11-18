package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A hotel offered while typing a hotel name; search it with its hotelId")
public record HotelSuggestionResponse(
        @Schema(description = "Namespaced hotel id, as a search's hotelId takes it", example = "3f2c...:100000123") String hotelId,
        @Schema(example = "Taj Exotica Resort & Spa") String name,
        @Schema(description = "The city to search it in", example = "Goa") String city,
        @Schema(example = "IN") String countryCode,
        @Schema(example = "India") String countryName,
        @Schema(description = "1-5, null when unknown", example = "5") Integer starRating,
        @Schema(description = "The supplier that lists it; hotels are never merged across suppliers", example = "Tripjack") String supplierName) {
}
