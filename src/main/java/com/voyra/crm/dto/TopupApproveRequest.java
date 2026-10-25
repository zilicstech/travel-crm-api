package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Digits(integer = 17, fraction = 2)
    @Schema(description = "Amount credited to the wallet; may differ from the requested amount", example = "49500.00")
    private BigDecimal approvedAmount;

    @Size(max = 1000)
    @Schema(description = "Optional note shown to the agency", example = "Bank charges deducted", nullable = true)
    private String note;
}
