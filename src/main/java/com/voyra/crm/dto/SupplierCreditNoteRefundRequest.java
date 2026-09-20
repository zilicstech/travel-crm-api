package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Records cash actually received back from the supplier against a recorded credit note's refundable balance.")
public class SupplierCreditNoteRefundRequest {

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Schema(example = "10920.00")
    private BigDecimal amount;

    @NotNull(message = "paymentMode is required")
    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @Schema(example = "UTR2026092098765")
    private String instrumentRef;

    @NotNull(message = "receivedOn is required")
    @Schema(example = "2026-09-20")
    private LocalDate receivedOn;

    @Schema(example = "Refund received for cancelled flight")
    private String notes;
}
