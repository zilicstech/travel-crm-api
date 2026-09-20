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
@Schema(description = "One vendor's payable bucketed by days past due - the Payables aging report")
public class ApAgeingRowResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "0.00")
    private BigDecimal current;

    @Schema(example = "13020.00")
    private BigDecimal days1To30;

    @Schema(example = "0.00")
    private BigDecimal days31To60;

    @Schema(example = "0.00")
    private BigDecimal days61To90;

    @Schema(example = "0.00")
    private BigDecimal days90Plus;

    @Schema(example = "13020.00")
    private BigDecimal totalPayableInr;
}
