package com.voyra.crm.dto;

import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.ServiceType;
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
@Schema(description = "Agent profile plus performance KPIs, computed from real Lead/Booking/Note data (never stored counters)")
public class AgentPerformanceResponse {

    @Schema(description = "Agent id", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Agent's display name", example = "Liam Smith")
    private String name;

    @Schema(description = "Agent's login email", example = "liam@globalexplorer.com")
    private String email;

    @Schema(description = "Agent's contact phone number", example = "+1 555 123 4567")
    private String phone;

    @Schema(description = "Agent's department. Display-only legacy field - manageableServices is what governs access.", example = "SALES")
    private AgentDepartment department;

    @Schema(description = "Which service types this agent may work on", example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> manageableServices;

    @Schema(description = "Whether the agent can currently log in", example = "true")
    private Boolean isActive;

    @Schema(description = "Total leads assigned to this agent", example = "12")
    private Long leadsAssigned;

    @Schema(description = "Assigned leads not yet Booked or Lost", example = "8")
    private Long activeLeads;

    @Schema(description = "Assigned leads that reached Booked status", example = "3")
    private Long bookedLeads;

    @Schema(description = "Leads that reached or passed the Proposal Sent stage")
    private Long quotationsSent;

    @Schema(description = "Notes/call-logs authored by this agent across all their leads")
    private Long notesLogged;

    @Schema(description = "Leads with a follow-up due today or overdue, still open")
    private Long pendingFollowUps;

    @Schema(description = "Total bookings made by this agent", example = "8")
    private Long bookingsCount;

    @Schema(description = "Total selling price across this agent's bookings", example = "52000.00")
    private BigDecimal revenueGenerated;

    @Schema(description = "Total profit across this agent's bookings", example = "10000.00")
    private BigDecimal profitGenerated;

    @Schema(description = "commissionRate% of profitGenerated")
    private BigDecimal commission;

    @Schema(description = "Percentage of booking profit paid as commission", example = "5.00")
    private BigDecimal commissionRate;

    @Schema(description = "bookedLeads / leadsAssigned * 100, rounded")
    private Integer conversionPercent;
}
