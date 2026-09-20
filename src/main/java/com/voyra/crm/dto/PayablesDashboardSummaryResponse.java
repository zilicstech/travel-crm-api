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
@Schema(description = "Payables dashboard tiles. Every figure is INR.")
public class PayablesDashboardSummaryResponse {

    @Schema(description = "SUM(balanceDueInr) across every non-cancelled approved bill, all time", example = "13020.00")
    private BigDecimal payableOutstandingInr;

    @Schema(description = "Of the outstanding total, the portion already past its due date", example = "0.00")
    private BigDecimal overdueInr;

    @Schema(description = "Advance/deposit payments not yet applied to a bill", example = "86980.00")
    private BigDecimal advancesHeldInr;

    @Schema(description = "GST recorded on approved bills this month - the agency's input tax", example = "620.00")
    private BigDecimal inputTaxThisMonthInr;

    @Schema(description = "Bills still in DRAFT, awaiting approval", example = "2")
    private Long billsAwaitingApprovalCount;

    @Schema(example = "50000.00")
    private BigDecimal billedThisMonthInr;

    @Schema(example = "13020.00")
    private BigDecimal paidThisMonthInr;
}
