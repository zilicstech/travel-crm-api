package com.voyra.crm.dto;

import com.voyra.crm.enums.AgentDepartment;
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
@Schema(description = "Agent profile plus performance KPIs, computed from real Lead/Booking/Note data (never stored counters)")
public class AgentPerformanceResponse {

    private String id;
    private String name;
    private String email;
    private String phone;
    private AgentDepartment department;
    private Boolean isActive;

    private Long leadsAssigned;
    private Long activeLeads;
    private Long bookedLeads;

    @Schema(description = "Leads that reached or passed the Proposal Sent stage")
    private Long quotationsSent;

    @Schema(description = "Notes/call-logs authored by this agent across all their leads")
    private Long notesLogged;

    @Schema(description = "Leads with a follow-up due today or overdue, still open")
    private Long pendingFollowUps;

    private Long bookingsCount;
    private BigDecimal revenueGenerated;
    private BigDecimal profitGenerated;

    @Schema(description = "commissionRate% of profitGenerated")
    private BigDecimal commission;
    private BigDecimal commissionRate;

    @Schema(description = "bookedLeads / leadsAssigned * 100, rounded")
    private Integer conversionPercent;
}
