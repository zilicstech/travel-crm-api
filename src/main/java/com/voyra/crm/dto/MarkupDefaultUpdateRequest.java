package com.voyra.crm.dto;

import com.voyra.crm.enums.MarkupMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Sets the agency's default markup for one service type - creates the row if none exists yet, overwrites it otherwise.")
public class MarkupDefaultUpdateRequest {

    @NotNull(message = "mode is required")
    @Schema(example = "PERCENT")
    private MarkupMode mode;

    @NotNull(message = "value is required")
    @DecimalMin(value = "0.0", message = "value cannot be negative")
    @Schema(example = "5.00")
    private BigDecimal value;
}
