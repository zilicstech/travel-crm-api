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
@Schema(description = "A client's credit position, all INR. Used = invoicedUnpaid + bookedNotInvoiced + cancellationCharges - advanceHeld.")
public class ClientCreditResponse {

    @Schema(description = "The agreed credit limit; null when the client has no limit", example = "1000000.00")
    private BigDecimal limit;

    @Schema(description = "Invoiced and not yet paid (positive ledger balance)", example = "350000.00")
    private BigDecimal invoicedUnpaid;

    @Schema(description = "Selling price of live bookings not yet on an issued tax invoice, before GST", example = "250000.00")
    private BigDecimal bookedNotInvoiced;

    @Schema(description = "Charges kept on bookings cancelled before they were invoiced", example = "10000.00")
    private BigDecimal cancellationCharges;

    @Schema(description = "Money the client has paid ahead (negative ledger balance)", example = "0.00")
    private BigDecimal advanceHeld;

    @Schema(description = "How much of the limit is in use", example = "610000.00")
    private BigDecimal used;

    @Schema(description = "Limit minus used; negative once the owner has allowed a booking over the limit; null when there is no limit", example = "390000.00")
    private BigDecimal available;
}
