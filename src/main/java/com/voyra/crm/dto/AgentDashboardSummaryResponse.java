package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Agent's own-scoped dashboard KPIs, live-computed from their assigned leads and bookings")
public class AgentDashboardSummaryResponse {

    @Schema(description = "Total leads assigned to this agent", example = "12")
    private long myLeadsCount;

    @Schema(description = "Leads assigned to this agent still in New status", example = "3")
    private long newLeadsCount;

    @Schema(description = "Leads with a follow-up due today or overdue, still open", example = "2")
    private long todayFollowUpsCount;

    @Schema(description = "Total bookings made by this agent", example = "8")
    private long bookingsCount;

    @Schema(description = "This agent's bookings still awaiting confirmation", example = "1")
    private long pendingBookingsCount;

    @Schema(description = "Total selling price across this agent's bookings", example = "52000.00")
    private BigDecimal revenue;

    @Schema(description = "Total profit across this agent's bookings", example = "10000.00")
    private BigDecimal profit;

    @Schema(description = "bookedLeads / myLeadsCount * 100, rounded", example = "25")
    private int conversionPercent;

    @Schema(description = "This agent's 5 most recently created bookings")
    private List<BookingResponse> recentBookings;

    @Schema(description = "This agent's open leads with an overdue or today-due follow-up")
    private List<LeadResponse> overdueFollowUps;
}
