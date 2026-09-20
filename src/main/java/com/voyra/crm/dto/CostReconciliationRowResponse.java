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
@Schema(description = "Read-only comparison of a booking's estimated cost (agent-entered, unchanged by payables - "
        + "see ARCHITECTURE-SPINE AD-9) against the sum of its approved supplier bills. No edit affordance anywhere "
        + "reads this row: it never writes back to booking.netCost/profit.")
public class CostReconciliationRowResponse {

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(description = "booking.netCost - the agent's own estimate, untouched by payables", example = "12000.00")
    private BigDecimal estimatedCostInr;

    @Schema(description = "SUM(grandTotalInr) of every approved, non-cancelled supplier bill against this booking", example = "13020.00")
    private BigDecimal actualCostInr;

    @Schema(example = "1020.00")
    private BigDecimal varianceInr;

    @Schema(description = "varianceInr / estimatedCostInr * 100, null when estimatedCostInr is zero", example = "8.50")
    private BigDecimal variancePercent;
}
