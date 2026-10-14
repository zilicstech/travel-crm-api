package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One physical flight with every fare option the supplier returned for it, cheapest first")
public class FlightGroupResponse {

    @Schema(description = "Stable within one search result: airline|flight number|departure|arrival")
    private String key;

    private String airline;
    private String flightNumber;
    private String origin;
    private String destination;
    private LocalTime departTime;
    private LocalTime arriveTime;

    @Schema(description = "As the supplier sent it - minutes (\"140\") or text (\"3h 05m\")")
    private String duration;

    @Schema(description = "0 = non-stop")
    private int stops;

    @Schema(description = "Lowest fare among the (filtered) options")
    private BigDecimal cheapestPrice;

    private List<FlightOfferResponse> fares;
}
