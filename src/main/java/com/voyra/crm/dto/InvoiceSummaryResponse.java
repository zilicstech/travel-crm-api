package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Invoice KPI summary. Owner sees agency-wide figures; Agent sees own client invoices only, with supplier figures zeroed.")
public class InvoiceSummaryResponse {

    @Schema(description = "Total amount collected from client invoices", example = "45000.00")
    private BigDecimal totalCollected;

    @Schema(description = "Total amount still owed by clients", example = "6800.00")
    private BigDecimal totalPendingToCollect;

    @Schema(description = "Total GST across client invoices", example = "8100.00")
    private BigDecimal totalGst;

    @Schema(description = "Owner-only. Total paid to suppliers", example = "30000.00")
    private BigDecimal totalPaidToSuppliers;

    @Schema(description = "Owner-only. Total still owed to suppliers", example = "12000.00")
    private BigDecimal totalPendingToPay;
}
