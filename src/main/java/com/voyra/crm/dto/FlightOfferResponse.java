package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Schema(description = "IATA airline code (e.g. 6E) - used for logos; may be blank", example = "6E")
    private String airlineCode;

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

    @Schema(description = "Local departure date", example = "2026-11-20")
    private LocalDate departDate;

    @Schema(description = "Local arrival date - later than departDate on an overnight flight", example = "2026-11-21")
    private LocalDate arriveDate;

    @Schema(description = "Human-readable total duration", example = "3h 05m")
    private String duration;

    @Schema(description = "0 = non-stop", example = "0")
    private int stops;

    @Schema(example = "Tripjack")
    private String supplier;

    @Schema(description = "Fare for ONE adult", example = "18000.00")
    private BigDecimal price;

    @Schema(description = "Fare for every searched passenger (adults, children and infants, each at "
            + "their own fare where the supplier quotes one) - what a proposal line or booking should cost",
            example = "45000.00")
    private BigDecimal totalPrice;

    @Schema(description = "How many passengers totalPrice covers", example = "3")
    private int passengers;

    @Schema(example = "INR")
    private String currency;

    @Schema(example = "false")
    private boolean refundable;

    @Schema(example = "15kg check-in")
    private String baggage;

    @Schema(example = "Saver")
    private String fareType;
}
