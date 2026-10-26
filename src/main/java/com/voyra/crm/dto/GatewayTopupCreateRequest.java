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
    @DecimalMax(value = "100000000", message = "A top-up can be at most ₹10,00,00,000")
    @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places") // the cap above names the size
    @Schema(description = "Amount to add, 1 to 10,00,00,000", example = "50000.00")
    private BigDecimal amount;
}
