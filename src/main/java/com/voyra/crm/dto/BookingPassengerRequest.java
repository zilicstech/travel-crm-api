package com.voyra.crm.dto;

import com.voyra.crm.enums.PaxType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "One traveller on the booking, with the fare attributed to them. A save "
        + "replaces the whole passenger list, same as InvoiceLineItemRequest replaces an "
        + "invoice's lines - see ACCOUNTING_REDESIGN_SPEC.md §5.1.")
public class BookingPassengerRequest {

    @Schema(description = "The traveller's row on the lead's manifest, when this booking came from a lead", example = "8f2a1c3d-...")
    private String leadMemberId;

    @NotBlank(message = "Passenger name is required")
    @Schema(example = "ABHISHEK KUMAR SINGH")
    private String passengerName;

    @Schema(example = "ADULT")
    private PaxType paxType;

    @NotNull(message = "Fare amount is required")
    @Schema(description = "This passenger's total fare - what their invoice line prints", example = "138996.00")
    private BigDecimal fareAmount;

    @Valid
    @Schema(description = "Itinerary legs, in travel order - only for a sector-wise service category (air, rail)")
    private List<BookingSectorRequest> sectors;
}
