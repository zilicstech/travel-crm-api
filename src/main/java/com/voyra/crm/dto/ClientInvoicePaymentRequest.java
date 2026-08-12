package com.voyra.crm.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ClientInvoicePaymentRequest {

    @NotNull(message = "Amount paid is required")
    private BigDecimal amountPaid;

    private String paymentMode;
}
