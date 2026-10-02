package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Income and expense account movement over a date range, with the net profit or loss for the period.")
public class ProfitAndLossResponse {

    @Schema(example = "2026-04-01")
    private LocalDate from;

    @Schema(example = "2026-09-30")
    private LocalDate to;

    @Schema(description = "INCOME accounts that moved in the range, sorted by account code.")
    private List<ProfitAndLossRowResponse> incomeRows;

    @Schema(description = "EXPENSE accounts that moved in the range, sorted by account code.")
    private List<ProfitAndLossRowResponse> expenseRows;

    @Schema(example = "420000.00")
    private BigDecimal totalIncomeInr;

    @Schema(example = "95000.00")
    private BigDecimal totalExpenseInr;

    @Schema(example = "325000.00")
    private BigDecimal netProfitInr;
}
