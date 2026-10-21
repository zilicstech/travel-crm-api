package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One hotel offer from a supplier search - not yet on the proposal until "
        + "the agent adds it via the batch proposal-item endpoint")
public class HotelOfferResponse {

    @Schema(description = "Opaque id for this offer within the search response, not a stored entity", example = "hoff-1")
    private String offerId;

    @Schema(description = "Supplier hotel id - opens the hotel's own page", example = "10000000012345")
    private String hotelId;

    @Schema(description = "The hotel's main photo, when the supplier has one")
    private String imageUrl;

    @Schema(description = "The search this offer came from - the hotel's page must quote it to the supplier")
    private String correlationId;

    @Schema(example = "Amari Phuket")
    private String hotelName;

    @Schema(description = "1-5", example = "4")
    private int starRating;

    @Schema(example = "Phuket")
    private String city;

    @Schema(example = "Patong Beach Road, Phuket")
    private String address;

    @Schema(example = "Deluxe Room")
    private String roomType;

    @Schema(example = "Breakfast Included")
    private String boardBasis;

    @Schema(description = "Total nights for the stay", example = "3")
    private int nights;

    @Schema(example = "Tripjack")
    private String supplier;

    @Schema(description = "Total price for the whole stay, all rooms included", example = "24000.00")
    private BigDecimal price;

    @Schema(example = "INR")
    private String currency;

    @Schema(example = "true")
    private boolean refundable;

    @Schema(example = "Free cancellation until 48 hours before check-in")
    private String cancellationPolicy;
}
