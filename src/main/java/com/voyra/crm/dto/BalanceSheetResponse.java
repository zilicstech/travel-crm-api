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
@Schema(description = "Cumulative asset, liability and equity balances as of a single date. Assets always equals Liabilities plus Equity - the period's P&L result is folded into Retained Earnings at report time to make this hold.")
public class BalanceSheetResponse {

    @Schema(example = "2026-09-30")
    private LocalDate asOf;

    @Schema(description = "ASSET accounts' cumulative balance as of the sheet's date, sorted by account code.")
    private List<BalanceSheetRowResponse> assetRows;

    @Schema(description = "LIABILITY accounts' cumulative balance as of the sheet's date, sorted by account code.")
    private List<BalanceSheetRowResponse> liabilityRows;

    @Schema(description = "EQUITY accounts' cumulative balance as of the sheet's date, with the period's net profit/loss folded into 3200, sorted by account code.")
    private List<BalanceSheetRowResponse> equityRows;

    @Schema(example = "725000.00")
    private BigDecimal totalAssetsInr;

    @Schema(example = "400000.00")
    private BigDecimal totalLiabilitiesInr;

    @Schema(example = "325000.00")
    private BigDecimal totalEquityInr;
}
