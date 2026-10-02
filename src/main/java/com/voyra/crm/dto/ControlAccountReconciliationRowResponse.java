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
@Schema(description = "A control account's GL balance against its subsidiary ledger total - zero variance is what makes it safe "
        + "to trust a GL-derived statement over the existing subsidiary ledgers (Rule 1.7.2).")
public class ControlAccountReconciliationRowResponse {

    @Schema(example = "1200")
    private String accountCode;

    @Schema(example = "Accounts Receivable")
    private String accountName;

    @Schema(example = "125000.00")
    private BigDecimal glBalanceInr;

    @Schema(example = "125000.00")
    private BigDecimal subsidiaryTotalInr;

    @Schema(example = "0.00")
    private BigDecimal varianceInr;
}
