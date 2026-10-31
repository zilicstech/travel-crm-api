package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Re-price a flight offer with the supplier before booking it")
public class FlightReviewRequest {

    @NotBlank
    @Schema(description = "Offer id from the flight search results", example = "mock-indigo-0615-PUBLISHED")
    private String offerId;

    @Min(1)
    @Max(9)
    @Schema(description = "Adults (12+)", example = "2")
    private int adults;

    @Min(0)
    @Max(9)
    @Schema(description = "Children (2-11)", example = "1")
    private int children;

    @Min(0)
    @Max(9)
    @Schema(description = "Infants (under 2)", example = "0")
    private int infants;

    @Schema(description = "Optional CRM client the booking is for", example = "c1d2e3f4-0000-0000-0000-000000000001")
    private String clientId;

    @Schema(description = "Optional CRM lead the booking belongs to", example = "l1d2e3f4-0000-0000-0000-000000000001")
    private String leadId;

    @Schema(description = "Optional lead service tab the booking was launched from", example = "s1d2e3f4-0000-0000-0000-000000000001")
    private String serviceId;

    @Schema(description = "Optional displayed fare the agency saw in the search results; when the review differs, priceChanged is true", example = "4500.00")
    private BigDecimal searchDisplayedFare;

    @Size(max = 60)
    @Schema(description = "Display hint: airline name; only the mock supplier uses it to draw a route", example = "IndiGo")
    private String airline;

    @Size(max = 3)
    @Schema(description = "Display hint: airline code; only the mock supplier uses it to draw a route", example = "6E")
    private String airlineCode;

    @Size(max = 8)
    @Schema(description = "Display hint: flight number; only the mock supplier uses it to draw a route", example = "201")
    private String flightNumber;

    @Size(max = 3)
    @Schema(description = "Display hint: origin airport code; only the mock supplier uses it to draw a route", example = "DEL")
    private String origin;

    @Size(max = 3)
    @Schema(description = "Display hint: destination airport code; only the mock supplier uses it to draw a route", example = "BOM")
    private String destination;

    @Size(max = 10)
    @Schema(description = "Display hint: departure date; only the mock supplier uses it to draw a route", example = "2026-12-01")
    private String departDate;

    @Size(max = 8)
    @Schema(description = "Display hint: departure time; only the mock supplier uses it to draw a route", example = "06:00")
    private String departTime;

    @Size(max = 8)
    @Schema(description = "Display hint: arrival time; only the mock supplier uses it to draw a route", example = "08:10")
    private String arriveTime;

    @Size(max = 200)
    @Schema(description = "Round trip: the return offer id from returnFlights, reviewed and booked with offerId as one booking; "
            + "omit for one-way and COMBO", example = "mock-indigo-06:15-PUBLISHED-ECONOMY-R")
    private String returnOfferId;

    @Size(max = 60)
    @Schema(description = "Display hint for the return leg: airline name; only the mock supplier uses it", example = "IndiGo")
    private String returnAirline;

    @Size(max = 3)
    @Schema(description = "Display hint for the return leg: airline code; only the mock supplier uses it", example = "6E")
    private String returnAirlineCode;

    @Size(max = 8)
    @Schema(description = "Display hint for the return leg: flight number; only the mock supplier uses it", example = "6E-206")
    private String returnFlightNumber;

    @Size(max = 10)
    @Schema(description = "Display hint for the return leg: departure date; only the mock supplier uses it", example = "2026-12-04")
    private String returnDepartDate;

    @Size(max = 8)
    @Schema(description = "Display hint for the return leg: departure time; only the mock supplier uses it", example = "18:00")
    private String returnDepartTime;

    @Size(max = 8)
    @Schema(description = "Display hint for the return leg: arrival time; only the mock supplier uses it", example = "20:10")
    private String returnArriveTime;
}
