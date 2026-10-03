package com.voyra.crm.dto;

import com.voyra.crm.enums.ControlAccountOf;
import com.voyra.crm.enums.LedgerAccountType;
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
@Schema(description = "One chart-of-accounts row with its cumulative balance as of a date.")
public class LedgerAccountBalanceResponse {

    @Schema(example = "1200")
    private String code;

    @Schema(example = "Accounts Receivable")
    private String name;

    @Schema(example = "ASSET")
    private LedgerAccountType accountType;

    @Schema(description = "Grouping header this account rolls up under, if any", example = "4000")
    private String parentCode;

    @Schema(description = "Seeded by the system - can be deactivated, never deleted", example = "true")
    private Boolean isSystem;

    @Schema(description = "Mirrors a subsidiary ledger - never the target of a manual journal", example = "true")
    private Boolean isControl;

    @Schema(example = "ACCOUNTS_RECEIVABLE")
    private ControlAccountOf controlOf;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(description = "Cumulative debits through the as-of date", example = "48500.00")
    private BigDecimal debitInr;

    @Schema(description = "Cumulative credits through the as-of date", example = "12000.00")
    private BigDecimal creditInr;

    @Schema(description = "Balance in the account's natural direction - debit minus credit for ASSET/EXPENSE, credit minus debit otherwise. Negative means the account is running against its normal side.",
            example = "36500.00")
    private BigDecimal balanceInr;
}
