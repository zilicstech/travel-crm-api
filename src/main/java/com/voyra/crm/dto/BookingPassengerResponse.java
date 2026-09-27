package com.voyra.crm.dto;

import com.voyra.crm.enums.PaxType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@Schema(description = "One traveller on the booking, with the fare attributed to them and their itinerary legs.")
public class BookingPassengerResponse {

    @Schema(example = "8f2a1c3d-...")
    private String id;

    @Schema(description = "The traveller's row on the lead's manifest, when this booking came from a lead", example = "8f2a1c3d-...")
    private String leadMemberId;

    @Schema(example = "ABHISHEK KUMAR SINGH")
    private String passengerName;

    @Schema(example = "ADULT")
    private PaxType paxType;

    @Schema(description = "This passenger's base fare - added to taxAmount for their invoice line's total", example = "138996.00")
    private BigDecimal fareAmount;

    @Schema(description = "The airline/supplier's own tax component (YQ/YR and similar) - printed as a separate Taxes column next to Fare", example = "0.00")
    private BigDecimal taxAmount;

    @Schema(description = "Itinerary legs, in travel order")
    private List<BookingSectorResponse> sectors;
}
