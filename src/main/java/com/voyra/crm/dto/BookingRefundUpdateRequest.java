package com.voyra.crm.dto;

import com.voyra.crm.enums.RefundState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Request body for updating a cancelled booking's refund state")
public class BookingRefundUpdateRequest {

    @NotNull(message = "refundState is required")
    @Schema(example = "REFUND_PENDING")
    private RefundState refundState;

    @DecimalMin(value = "0.00", message = "Refund amount cannot be negative")
    @Schema(description = "Set when the refund is partial or complete", example = "42000.00")
    private BigDecimal refundAmount;

    @Schema(description = "When the supplier owes the refund by", example = "2026-10-01")
    private LocalDate refundDueDate;
}
