package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Records a payment against an issued invoice, a partially paid invoice, or an "
        + "issued proforma (advance) - or, with invoiceId omitted and clientId supplied instead, a "
        + "deposit on account with no invoice at all (the customer wallet). Currency and FX rate for "
        + "an invoice-attached receipt are always the invoice's own; a deposit is always INR.")
public class PaymentReceiptRequest {

    @Schema(description = "Omit for a deposit on account - clientId is then required instead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String invoiceId;

    @Schema(description = "Required when invoiceId is omitted - the client this deposit belongs to", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

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

    @Schema(description = "Set only when this receipt came in through a payment gateway", example = "Razorpay")
    private String gatewayProvider;

    @Schema(example = "pay_P8qN2xK3Jd")
    private String gatewayTxnRef;

    @Schema(description = "The gateway's processing fee, in the receipt's own currency - the invoice is still "
            + "credited the full gross amount (Rule 5.3); this is posted separately as an agency expense", example = "840.00")
    private BigDecimal gatewayFee;

    @NotNull(message = "receivedOn is required")
    @Schema(example = "2026-09-19")
    private LocalDate receivedOn;

    @Schema(example = "Advance for Bali package")
    private String notes;
}
