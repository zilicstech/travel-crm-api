package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One search result's static extras - filled into the list once the supplier's content call answers")
public class HotelThumbnailResponse {

    @Schema(description = "Supplier hotel id", example = "10000000012345")
    private String hotelId;

    @Schema(description = "A small main photo, when the supplier has one")
    private String imageUrl;

    @Schema(description = "1-5, 0 when unknown", example = "4")
    private int starRating;

    @Schema(example = "Patong Beach Road, Phuket")
    private String address;
}
