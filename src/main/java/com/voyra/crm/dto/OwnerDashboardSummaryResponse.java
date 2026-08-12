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
public class OwnerDashboardSummaryResponse {

    private BigDecimal totalRevenue;
    private BigDecimal totalProfit;
    private BigDecimal totalNetCost;
    private long activeClients;
    private long totalLeads;
    private long bookedLeads;
    private long lostLeads;
    private int conversionRate;
    private BigDecimal pendingPayments;
    private long overdueFollowUps;
    private long pendingBookings;
    private long confirmedBookings;
}
