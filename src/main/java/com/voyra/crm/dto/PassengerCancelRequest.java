package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cancels some travellers of a flight ticket, the booking kept for the rest (client 27 Nov)")
public class PassengerCancelRequest {

    @NotEmpty(message = "Pick the travellers to cancel")
    @Schema(description = "Member ids of travellers on one e-ticket of the booking", example = "[\"m1\"]")
    private List<String> travellerIds;

    @NotNull(message = "Enter their share of the selling price")
    @DecimalMin(value = "0", message = "The share can't be negative")
    @Schema(description = "Their share of the ticket's selling price, in base currency", example = "15900.00")
    private BigDecimal sellingShare;

    @NotNull(message = "Enter their share of the purchase price")
    @DecimalMin(value = "0", message = "The share can't be negative")
    @Schema(description = "Their share of the ticket's purchase price, in base currency", example = "14575.00")
    private BigDecimal purchaseShare;

    @DecimalMin(value = "0", message = "Charges can't be negative")
    @Schema(description = "Kept by the airline", example = "3000.00")
    private BigDecimal airlineCharge;

    @DecimalMin(value = "0", message = "Charges can't be negative")
    @Schema(description = "Kept by the supplier", example = "200.00")
    private BigDecimal supplierCharge;

    @DecimalMin(value = "0", message = "Charges can't be negative")
    @Schema(description = "Kept by the agency, no GST (owner ruling 2026-11-27)", example = "500.00")
    private BigDecimal agencyFee;

    @Size(max = 300, message = "Write at most 300 characters")
    @Schema(example = "Client's mother is unwell")
    private String reason;
}
