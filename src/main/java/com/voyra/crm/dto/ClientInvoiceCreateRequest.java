package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ClientInvoiceCreateRequest {

    @NotBlank(message = "Customer is required")
    private String customerId;

    @Schema(description = "Owner-only: attribute the invoice to a specific agent. Ignored for the AGENT role (always self).")
    private String agentId;

    @NotNull(message = "Amount is required")
    private BigDecimal amount;

    @Schema(description = "GST %, India standard rate default", defaultValue = "18.00")
    private BigDecimal gstRate;

    private LocalDate dueDate;
    private String paymentMode;
}
