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
@Schema(description = "One income or expense account's net movement over the P&L's date range - credit minus debit for an INCOME account, debit minus credit for an EXPENSE account.")
public class ProfitAndLossRowResponse {

    @Schema(example = "4010")
    private String accountCode;

    @Schema(example = "Sales A/c (Package)")
    private String accountName;

    @Schema(example = "185000.00")
    private BigDecimal netAmountInr;
}
