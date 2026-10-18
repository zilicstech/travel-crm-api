package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import com.voyra.crm.enums.CancellationType;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Cancel a whole booking. Refunds are worked out from what the customer paid and what the supplier and the agency keep.")
public class BookingCancelRequest {

    @NotNull(message = "Cancellation type is required")
    @Schema(example = "VOID")
    private CancellationType type;

    @Schema(description = "Optional; defaults to the type's label", example = "Client changed plans")
    private String reason;

    @DecimalMin(value = "0.0", message = "Airline charge cannot be negative")
    @Schema(description = "What the airline (or hotel) keeps", example = "1500.00")
    private BigDecimal airlineCharge;

    @DecimalMin(value = "0.0", message = "Supplier fee cannot be negative")
    @Schema(description = "The supplier's own cancellation fee (e.g. Tripjack's), on top of the airline's charge", example = "500.00")
    private BigDecimal supplierCharge;

    @DecimalMin(value = "0.0", message = "Agency fee cannot be negative")
    @Schema(description = "The agency's own cancellation fee", example = "500.00")
    private BigDecimal agencyFee;
}
