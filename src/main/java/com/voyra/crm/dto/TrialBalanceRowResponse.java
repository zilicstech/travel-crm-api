package com.voyra.crm.dto;

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
@Schema(description = "One ledger account's movement over the Trial Balance's date range.")
public class TrialBalanceRowResponse {

    @Schema(example = "1200")
    private String accountCode;

    @Schema(example = "Accounts Receivable")
    private String accountName;

    @Schema(example = "ASSET")
    private LedgerAccountType accountType;

    @Schema(description = "Null for every account today - no chart-of-accounts row currently sets parentCode.", example = "null")
    private String parentCode;

    @Schema(example = "48500.00")
    private BigDecimal debitInr;

    @Schema(example = "12000.00")
    private BigDecimal creditInr;
}
