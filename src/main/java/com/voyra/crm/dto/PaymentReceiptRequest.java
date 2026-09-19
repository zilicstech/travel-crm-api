package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Records a payment against an issued invoice, a partially paid invoice, or an issued proforma (advance). Currency and FX rate are always the invoice's own - there is no field to enter them independently, since a payment in another currency is rejected, never converted.")
public class PaymentReceiptRequest {

    @NotBlank(message = "invoiceId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String invoiceId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Schema(example = "42000.00")
    private BigDecimal amount;

    @NotNull(message = "paymentMode is required")
    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @Schema(example = "UTR2026091912345")
    private String instrumentRef;

    @Schema(example = "HDFC Current A/c 001")
    private String bankAccountLabel;

    @NotNull(message = "receivedOn is required")
    @Schema(example = "2026-09-19")
    private LocalDate receivedOn;

    @Schema(example = "Advance for Bali package")
    private String notes;
}
