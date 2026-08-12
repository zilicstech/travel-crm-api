package com.voyra.crm.controller;

import com.voyra.crm.dto.AgentDashboardSummaryResponse;
import com.voyra.crm.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/agent")
@RequiredArgsConstructor
@Tag(name = "Agent - Dashboard", description = "Own-scoped KPI summary")
@PreAuthorize("hasRole('AGENT')")
public class AgentDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "My dashboard KPIs", description = "Own leads, follow-ups, bookings, revenue, conversion - scoped server-side to the calling agent.")
    public ResponseEntity<AgentDashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(dashboardService.getAgentSummary());
    }
}
