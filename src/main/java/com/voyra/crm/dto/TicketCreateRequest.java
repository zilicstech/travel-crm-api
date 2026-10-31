package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Download a customer e-ticket; the markup is folded into the printed price, never shown")
public class TicketCreateRequest {

    @DecimalMin(value = "0", message = "Markup cannot be negative")
    @Digits(integer = 9, fraction = 2, message = "Markup can have at most two decimals")
    @Schema(description = "Rupees added to the published fare for the client; blank means 0, at most the published fare", example = "500.00")
    private BigDecimal markup;
}
