package com.voyra.crm.dto;

import com.voyra.crm.enums.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "One of Voyra's subscription invoices to the agency")
public class SubscriptionInvoiceResponse {

    @Schema(description = "Invoice id")
    private String id;

    @Schema(description = "VOY-SUB-yyyy-seq", example = "VOY-SUB-2026-1", nullable = true)
    private String number;

    @Schema(description = "Period start")
    private Instant periodStart;

    @Schema(description = "Period end")
    private Instant periodEnd;

    @Schema(description = "Taxable value, excluding GST", example = "4999.00")
    private BigDecimal amount;

    @Schema(description = "GST at 18 % (cgst + sgst, or igst)", example = "899.82")
    private BigDecimal gst;

    @Schema(description = "CGST 9 % when the agency is in Voyra's state, else 0", example = "449.91")
    private BigDecimal cgst;

    @Schema(description = "SGST 9 % when the agency is in Voyra's state, else 0", example = "449.91")
    private BigDecimal sgst;

    @Schema(description = "IGST 18 % when the agency is in another (or unknown) state, else 0", example = "0.00")
    private BigDecimal igst;

    @Schema(description = "Total including GST", example = "5898.82")
    private BigDecimal total;

    @Schema(description = "ISSUED, PAID or VOID")
    private String status;

    @Schema(description = "Due date", nullable = true)
    private Instant dueAt;

    @Schema(description = "When it was paid", nullable = true)
    private Instant paidAt;

    @Schema(description = "GATEWAY or BANK", nullable = true)
    private String paidVia;

    @Schema(description = "On an upgrade invoice: the plan paying it switches to (LITE or SUITE)", nullable = true)
    private SubscriptionPlan targetPlan;
}
