package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A Client Invoice, with GST and payment status always server-derived")
public class ClientInvoiceResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Denormalized snapshot, live-synced on client rename", example = "Jane Doe")
    private String clientName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(description = "Absent for a standalone client invoice not scoped to any lead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "Absent unless this invoice covers one service instance on the lead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Denormalized snapshot of the service's label at creation time", example = "Flight — BOM–BKK")
    private String serviceLabel;

    @Schema(description = "Free-text description of what this invoice covers", example = "Flight tickets - Mumbai to Bangkok")
    private String description;

    @Schema(description = "Invoice amount before GST", example = "10000.00")
    private BigDecimal amount;

    @Schema(description = "Server-computed GST amount", example = "1800.00")
    private BigDecimal gst;

    @Schema(description = "amount + gst", example = "11800.00")
    private BigDecimal totalWithGst;

    @Schema(description = "Cumulative amount paid to date", example = "5000.00")
    private BigDecimal amountPaid;

    @Schema(description = "totalWithGst minus amountPaid", example = "6800.00")
    private BigDecimal pending;

    @Schema(description = "Always server-derived from amountPaid, never client-set", example = "PARTIAL")
    private InvoiceStatus status;

    @Schema(example = "2026-08-13")
    private LocalDate invoiceDate;

    @Schema(example = "2026-09-15")
    private LocalDate dueDate;

    @Schema(example = "Bank Transfer")
    private String paymentMode;

    @Schema(description = "True when dueDate has passed and pending > 0")
    private boolean overdue;
}
