package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Request body for recording a payment against a client invoice")
public class ClientInvoicePaymentRequest {

    @NotNull(message = "Amount paid is required")
    @Schema(description = "Cumulative amount paid to date (not incremental)", example = "5000.00")
    private BigDecimal amountPaid;

    @Schema(example = "Bank Transfer")
    private String paymentMode;
}
