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
@Schema(description = "Owner's agency-wide dashboard KPIs, live-computed from all bookings, leads, and invoices")
public class OwnerDashboardSummaryResponse {

    @Schema(description = "Total selling price across all bookings", example = "52000.00")
    private BigDecimal totalRevenue;

    @Schema(description = "Total profit across all bookings", example = "10000.00")
    private BigDecimal totalProfit;

    @Schema(description = "Total cost paid to suppliers across all bookings", example = "42000.00")
    private BigDecimal totalNetCost;

    @Schema(description = "Customers with status CUSTOMER", example = "2")
    private long activeClients;

    @Schema(example = "4")
    private long totalLeads;

    @Schema(example = "0")
    private long bookedLeads;

    @Schema(example = "1")
    private long lostLeads;

    @Schema(description = "bookedLeads / totalLeads * 100, rounded", example = "0")
    private int conversionRate;

    @Schema(description = "Total outstanding across all non-PAID client invoices", example = "6800.00")
    private BigDecimal pendingPayments;

    @Schema(description = "Open leads with a follow-up due today or overdue", example = "1")
    private long overdueFollowUps;

    @Schema(example = "2")
    private long pendingBookings;

    @Schema(example = "5")
    private long confirmedBookings;
}
