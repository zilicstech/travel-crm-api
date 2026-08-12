package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDashboardSummaryResponse {

    private long myLeadsCount;
    private long newLeadsCount;
    private long todayFollowUpsCount;
    private long bookingsCount;
    private long pendingBookingsCount;
    private BigDecimal revenue;
    private BigDecimal profit;
    private int conversionPercent;

    private List<BookingResponse> recentBookings;
    private List<LeadResponse> overdueFollowUps;
}
