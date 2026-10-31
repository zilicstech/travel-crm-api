package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Set the CRM selling price of a Voyra booking to the published fare plus this markup, without a ticket")
public class PlatformMarkupRequest {

    @NotNull(message = "Markup is required")
    @DecimalMin(value = "0", message = "Markup cannot be negative")
    @Digits(integer = 9, fraction = 2, message = "Markup can have at most two decimals")
    @Schema(description = "Rupees added to the published fare for the client; 0 sets the published fare, at most the published fare", example = "500.00")
    private BigDecimal markup;
}
