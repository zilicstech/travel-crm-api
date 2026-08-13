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
@Schema(description = "One month's revenue and profit, for the revenue-trend report")
public class MonthlyRevenuePoint {

    @Schema(example = "2026-08")
    private String month;

    @Schema(description = "Sum of booking selling prices for the month", example = "52000.00")
    private BigDecimal revenue;

    @Schema(description = "Sum of booking profit for the month", example = "10000.00")
    private BigDecimal profit;
}
