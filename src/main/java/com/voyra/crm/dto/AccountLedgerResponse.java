package com.voyra.crm.dto;

import com.voyra.crm.enums.LedgerAccountType;
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
@Schema(description = "One account's General Ledger over a date range: opening balance, every movement with a running balance, closing balance.")
public class AccountLedgerResponse {

    @Schema(example = "1200")
    private String accountCode;

    @Schema(example = "Accounts Receivable")
    private String accountName;

    @Schema(example = "ASSET")
    private LedgerAccountType accountType;

    @Schema(example = "true")
    private Boolean isControl;

    @Schema(example = "2026-09-01")
    private LocalDate from;

    @Schema(example = "2026-09-30")
    private LocalDate to;

    @Schema(description = "Balance brought forward from before the range, natural direction", example = "36500.00")
    private BigDecimal openingBalanceInr;

    @Schema(example = "50000.00")
    private BigDecimal totalDebitInr;

    @Schema(example = "12000.00")
    private BigDecimal totalCreditInr;

    @Schema(description = "Opening balance plus the range's net movement, natural direction", example = "74500.00")
    private BigDecimal closingBalanceInr;

    @Schema(description = "Movements in date then entry-number order")
    private List<AccountLedgerLineResponse> lines;
}
