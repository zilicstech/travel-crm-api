package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "An online (gateway) top-up: just the amount to pay")
public class GatewayTopupCreateRequest {

    @NotNull
    @DecimalMin("1")
    @DecimalMax("100000000")
    @Digits(integer = 9, fraction = 2)
    @Schema(description = "Amount to add, 1 to 10,00,00,000", example = "50000.00")
    private BigDecimal amount;
}
