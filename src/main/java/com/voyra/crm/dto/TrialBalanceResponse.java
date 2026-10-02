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
@Schema(description = "Every ledger account's debit/credit movement over a date range, grouped by account type. Total debit always equals total credit - every posting is balance-checked before it is ever written.")
public class TrialBalanceResponse {

    @Schema(example = "2026-04-01")
    private LocalDate from;

    @Schema(example = "2026-09-30")
    private LocalDate to;

    @Schema(description = "One row per ledger account that moved in the range, sorted by account code.")
    private List<TrialBalanceRowResponse> rows;

    @Schema(example = "612400.00")
    private BigDecimal totalDebitInr;

    @Schema(example = "612400.00")
    private BigDecimal totalCreditInr;
}
