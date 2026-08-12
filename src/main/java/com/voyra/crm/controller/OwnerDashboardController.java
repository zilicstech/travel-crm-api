package com.voyra.crm.controller;

import com.voyra.crm.dto.OwnerDashboardSummaryResponse;
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
@RequestMapping("/api/dashboard/owner")
@RequiredArgsConstructor
@Tag(name = "Owner - Dashboard", description = "Agency-wide KPI summary")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class OwnerDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Agency-wide dashboard KPIs", description = "Revenue, profit, leads, conversion, pending payments, bookings - all live-computed, not client-side math.")
    public ResponseEntity<OwnerDashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(dashboardService.getOwnerSummary());
    }
}
