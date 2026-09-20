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
@Schema(description = "Records a payment to a vendor. Either supplierInvoiceId (paying down a bill) or "
        + "neither field (a pure advance/deposit) - never both a bookingId and no invoice unless it's an advance tied to future bookings with that vendor.")
public class SupplierPaymentRequest {

    @NotBlank(message = "vendorId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(description = "Omit for a pure advance/deposit top-up", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String supplierInvoiceId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Schema(example = "13020.00")
    private BigDecimal amount;

    @Schema(example = "0.00")
    private BigDecimal tdsWithheld;

    @NotNull(message = "paymentMode is required")
    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @Schema(example = "UTR2026091912345")
    private String instrumentRef;

    @Schema(example = "HDFC Current A/c 001")
    private String bankAccountLabel;

    @NotNull(message = "paidOn is required")
    @Schema(example = "2026-09-19")
    private LocalDate paidOn;

    @Schema(example = "Advance for October bookings")
    private String notes;
}
