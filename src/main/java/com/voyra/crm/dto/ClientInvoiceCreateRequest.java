package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Request body for creating a Client Invoice")
public class ClientInvoiceCreateRequest {

    @NotBlank(message = "Client is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Owner-only: attribute the invoice to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String agentId;

    @NotNull(message = "Amount is required")
    @Schema(description = "Invoice amount before GST", example = "10000.00")
    private BigDecimal amount;

    @Schema(description = "GST %, India standard rate default", defaultValue = "18.00", example = "18.00")
    private BigDecimal gstRate;

    @Schema(example = "2026-09-15")
    private LocalDate dueDate;

    @Schema(example = "Bank Transfer")
    private String paymentMode;

    @Schema(description = "Set when this invoice is scoped to one lead rather than a standalone client charge", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "Set when this invoice covers one service instance on the lead - requires leadId", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Free-text description of what this invoice covers", example = "Flight tickets - Mumbai to Bangkok")
    private String description;
}
