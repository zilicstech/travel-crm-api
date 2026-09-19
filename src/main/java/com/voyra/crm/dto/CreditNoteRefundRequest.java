package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Pays out cash against an issued credit note's refundable balance. Currency and FX rate are always the credit note's own, inherited from its invoice.")
public class CreditNoteRefundRequest {

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Schema(example = "20000.00")
    private BigDecimal amount;

    @NotNull(message = "paymentMode is required")
    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @Schema(example = "UTR2026091998765")
    private String instrumentRef;

    @NotNull(message = "receivedOn is required")
    @Schema(example = "2026-09-19")
    private LocalDate receivedOn;

    @Schema(example = "Refund for cancelled Bali package")
    private String notes;
}
