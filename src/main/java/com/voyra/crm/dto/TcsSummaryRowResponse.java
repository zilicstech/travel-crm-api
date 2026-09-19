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
@Schema(description = "TCS collected under one statutory section, summed over issued, non-cancelled tax invoices in the requested date range. Never counted as revenue or profit.")
public class TcsSummaryRowResponse {

    @Schema(example = "206C(1G)")
    private String tcsSection;

    @Schema(example = "5.000")
    private BigDecimal tcsRatePercent;

    @Schema(example = "230000.00")
    private BigDecimal tcsBaseAmountInr;

    @Schema(example = "11500.00")
    private BigDecimal tcsAmountInr;
}
