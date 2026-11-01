package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One physical flight with every fare option the supplier returned for it, cheapest first")
public class FlightGroupResponse {

    @Schema(description = "Stable within one search result: airline code|flight number|departure|arrival per leg, then direction")
    private String key;

    @Schema(description = "Airline name")
    private String airline;
    @Schema(description = "IATA airline code, null when the supplier sent none")
    private String airlineCode;
    @Schema(description = "Flight number of the first leg")
    private String flightNumber;
    @Schema(description = "Origin airport code")
    private String origin;
    @Schema(description = "Destination airport code")
    private String destination;
    @Schema(description = "Local departure time")
    private LocalTime departTime;
    @Schema(description = "Local arrival time")
    private LocalTime arriveTime;

    @Schema(description = "Local departure date", example = "2026-11-20")
    private LocalDate departDate;

    @Schema(description = "Local arrival date - later than departDate on an overnight flight", example = "2026-11-21")
    private LocalDate arriveDate;

    @Schema(description = "As the supplier sent it - minutes (\"140\") or text (\"3h 05m\")")
    private String duration;

    @Schema(description = "0 = non-stop")
    private int stops;

    @Schema(description = "ONWARD, RETURN or COMBO")
    private com.voyra.crm.enums.FlightDirection direction;

    @Schema(description = "Each direction this flight flies; one leg except on a COMBO round trip")
    private List<FlightLegResponse> legs;

    @Schema(description = "Displayed fare of the leading option: the cheapest fare of a supplier taking bookings, else the cheapest")
    private BigDecimal cheapestPrice;

    @Schema(description = "Supplier of the leading option", example = "Tripjack")
    private String cheapestSupplierName;

    @Schema(description = "How many suppliers have a fare on this flight (after filters)", example = "2")
    private int supplierCount;

    @Schema(description = "Every fare option for this flight, cheapest first")
    private List<FlightOfferResponse> fares;
}
