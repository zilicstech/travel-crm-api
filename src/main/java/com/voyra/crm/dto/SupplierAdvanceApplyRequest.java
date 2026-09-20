package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Applies part or all of an existing advance payment to a bill. This moves only the "
        + "bill's balance - no ledger row is posted, because the advance already posted its debit and the "
        + "bill already posted its credit (ARCHITECTURE-SPINE AD-5).")
public class SupplierAdvanceApplyRequest {

    @NotBlank(message = "advancePaymentId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String advancePaymentId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Schema(example = "13020.00")
    private BigDecimal amount;
}
