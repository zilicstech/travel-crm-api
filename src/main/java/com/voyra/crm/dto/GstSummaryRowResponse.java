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
@Schema(description = "One SAC-code/rate group of the GST register, summed over issued, non-cancelled tax invoice lines in the requested date range.")
public class GstSummaryRowResponse {

    @Schema(example = "9985")
    private String sacCode;

    @Schema(example = "5.000")
    private BigDecimal gstRatePercent;

    @Schema(example = "84000.00")
    private BigDecimal taxableValueInr;

    @Schema(example = "2100.00")
    private BigDecimal cgstAmountInr;

    @Schema(example = "2100.00")
    private BigDecimal sgstAmountInr;

    @Schema(example = "0.00")
    private BigDecimal igstAmountInr;

    @Schema(example = "4200.00")
    private BigDecimal gstTotalInr;
}
