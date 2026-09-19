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
@Schema(description = "Accounts dashboard tiles. Every figure is INR; \"this month\" is the calendar month containing today.")
public class AccountsDashboardSummaryResponse {

    @Schema(example = "153201.60")
    private BigDecimal billedThisMonthInr;

    @Schema(example = "55120.00")
    private BigDecimal collectedThisMonthInr;

    @Schema(description = "SUM(balanceDueInr) across every non-cancelled tax invoice, all time", example = "165881.60")
    private BigDecimal outstandingInr;

    @Schema(description = "Of the outstanding total, the portion already past its due date", example = "0.00")
    private BigDecimal overdueInr;

    @Schema(description = "Advance receipts recorded against an issued proforma not yet converted to a tax invoice", example = "0.00")
    private BigDecimal advanceHeldInr;

    @Schema(description = "GST raised this month on issued tax invoices - collected on the agency's behalf, not its revenue", example = "9201.60")
    private BigDecimal outputTaxThisMonthInr;
}
