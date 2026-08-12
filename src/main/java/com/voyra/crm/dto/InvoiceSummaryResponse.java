package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSummaryResponse {

    private BigDecimal totalCollected;
    private BigDecimal totalPendingToCollect;
    private BigDecimal totalGst;
    private BigDecimal totalPaidToSuppliers;
    private BigDecimal totalPendingToPay;
}
