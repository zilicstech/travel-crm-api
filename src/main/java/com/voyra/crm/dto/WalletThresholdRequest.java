package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Low-balance alert threshold")
public class WalletThresholdRequest {

    @DecimalMin("0")
    @Digits(integer = 17, fraction = 2)
    @Schema(description = "Alert when available falls below this; null turns the alert off", example = "10000.00", nullable = true)
    private BigDecimal lowBalanceThreshold;
}
