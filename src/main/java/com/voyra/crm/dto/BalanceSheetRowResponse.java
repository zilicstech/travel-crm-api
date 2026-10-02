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
@Schema(description = "One asset, liability or equity account's cumulative balance as of the sheet's date. The 3200 Retained Earnings row includes the period-to-date net profit/loss folded in at report time - it is never posted to the ledger.")
public class BalanceSheetRowResponse {

    @Schema(example = "1200")
    private String accountCode;

    @Schema(example = "Accounts Receivable")
    private String accountName;

    @Schema(example = "48500.00")
    private BigDecimal balanceInr;
}
