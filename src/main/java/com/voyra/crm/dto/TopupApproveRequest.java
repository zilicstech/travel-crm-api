package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Approve a bank top-up for the amount that actually reached the bank")
public class TopupApproveRequest {

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @DecimalMax(value = "100000000", message = "A top-up can be at most ₹10,00,00,000")
    @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places")
    @Schema(description = "Amount credited to the wallet; may differ from the requested amount", example = "49500.00")
    private BigDecimal approvedAmount;

    @Size(max = 1000)
    @Schema(description = "Optional note shown to the agency", example = "Bank charges deducted", nullable = true)
    private String note;
}
