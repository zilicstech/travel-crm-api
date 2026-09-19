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
@Schema(description = "One row of the Customers (ledger) list - billed/received/outstanding/advance, all INR.")
public class ClientLedgerSummaryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(description = "Lifetime billed - SUM of issued, non-cancelled tax invoices' grandTotalInr", example = "55120.00")
    private BigDecimal billedInr;

    @Schema(description = "Lifetime received - SUM of receipt amountInr, advances included", example = "30000.00")
    private BigDecimal receivedInr;

    @Schema(description = "Positive running balance owed to the agency", example = "25120.00")
    private BigDecimal outstandingInr;

    @Schema(description = "Negative running balance - a credit the agency owes this client", example = "0.00")
    private BigDecimal advanceInr;
}
