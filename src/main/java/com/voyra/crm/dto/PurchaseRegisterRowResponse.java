package com.voyra.crm.dto;

import com.voyra.crm.enums.ItcEligibility;
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
@Schema(description = "One SAC-code/rate/ITC-eligibility group of the Purchase & ITC register, summed over approved, non-cancelled supplier bills in the requested date range.")
public class PurchaseRegisterRowResponse {

    @Schema(example = "9971")
    private String sacCode;

    @Schema(example = "5.000")
    private BigDecimal gstRatePercent;

    @Schema(example = "ELIGIBLE")
    private ItcEligibility itcEligibility;

    @Schema(example = "12400.00")
    private BigDecimal taxableValueInr;

    @Schema(example = "310.00")
    private BigDecimal cgstAmountInr;

    @Schema(example = "310.00")
    private BigDecimal sgstAmountInr;

    @Schema(example = "0.00")
    private BigDecimal igstAmountInr;

    @Schema(example = "620.00")
    private BigDecimal gstTotalInr;
}
