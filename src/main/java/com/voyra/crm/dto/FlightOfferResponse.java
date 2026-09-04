package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One flight offer from a supplier search - not yet on the proposal until "
        + "the agent adds it via the batch proposal-item endpoint")
public class FlightOfferResponse {

    @Schema(description = "Opaque id for this offer within the search response, not a stored entity", example = "off-1")
    private String offerId;

    @Schema(example = "IndiGo")
    private String airline;

    @Schema(example = "6E-204")
    private String flightNumber;

    @Schema(example = "Bangalore")
    private String origin;

    @Schema(example = "Phuket")
    private String destination;

    @Schema(example = "06:15")
    private LocalTime departTime;

    @Schema(example = "09:20")
    private LocalTime arriveTime;

    @Schema(description = "Human-readable total duration", example = "3h 05m")
    private String duration;

    @Schema(description = "0 = non-stop", example = "0")
    private int stops;

    @Schema(example = "Tripjack")
    private String supplier;

    @Schema(example = "18000.00")
    private BigDecimal price;

    @Schema(example = "INR")
    private String currency;

    @Schema(example = "false")
    private boolean refundable;

    @Schema(example = "15kg check-in")
    private String baggage;

    @Schema(example = "Saver")
    private String fareType;
}
