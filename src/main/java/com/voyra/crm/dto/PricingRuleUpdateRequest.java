package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "A rule's new name and value; kind and product never change")
public class PricingRuleUpdateRequest {

    @NotBlank(message = "name is required")
    @Size(max = 60, message = "name must be at most 60 characters")
    @Schema(example = "Silver")
    private String name;

    @NotNull(message = "pct is required")
    @DecimalMin(value = "0", message = "pct must be between 0 and 100")
    @DecimalMax(value = "100", message = "pct must be between 0 and 100")
    @Digits(integer = 3, fraction = 2, message = "pct must have at most 2 decimals")
    @Schema(example = "3.50")
    private BigDecimal pct;

    @Schema(description = "Commission rules only", example = "false")
    private boolean allowAboveEarned;
}
