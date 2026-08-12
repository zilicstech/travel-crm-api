package com.voyra.crm.controller;

import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.CategoryCountResponse;
import com.voyra.crm.dto.MonthlyRevenuePoint;
import com.voyra.crm.service.AgentService;
import com.voyra.crm.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Owner-only reports (Reports Center) - revenue, employee, leads, bookings analytics + CSV export. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Owner - Reports", description = "Aggregate analytics and CSV export")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class ReportController {

    private final ReportService reportService;
    private final AgentService agentService;

    @GetMapping("/revenue-trend")
    @Operation(summary = "Monthly revenue/profit trend", description = "Real time series grouped from booking data, not hardcoded.")
    public ResponseEntity<List<MonthlyRevenuePoint>> getRevenueTrend(
            @RequestParam(value = "months", defaultValue = "6") int months) {
        return ResponseEntity.ok(reportService.getRevenueTrend(months));
    }

    @GetMapping("/agent-leaderboard")
    @Operation(summary = "Agent leaderboard", description = "Revenue, conversion %, bookings per agent.")
    public ResponseEntity<List<AgentPerformanceResponse>> getAgentLeaderboard() {
        return ResponseEntity.ok(agentService.listAgents());
    }

    @GetMapping("/lead-pipeline")
    @Operation(summary = "Lead counts by status")
    public ResponseEntity<List<CategoryCountResponse>> getLeadPipeline() {
        return ResponseEntity.ok(reportService.getLeadPipeline());
    }

    @GetMapping("/lead-source-distribution")
    @Operation(summary = "Lead counts by source")
    public ResponseEntity<List<CategoryCountResponse>> getLeadSourceDistribution() {
        return ResponseEntity.ok(reportService.getLeadSourceDistribution());
    }

    @GetMapping("/booking-type-distribution")
    @Operation(summary = "Booking counts by type")
    public ResponseEntity<List<CategoryCountResponse>> getBookingTypeDistribution() {
        return ResponseEntity.ok(reportService.getBookingTypeDistribution());
    }

    @GetMapping("/conversion-funnel")
    @Operation(summary = "Lead conversion funnel", description = "Cumulative counts of leads that reached each stage or beyond (Lost excluded).")
    public ResponseEntity<List<CategoryCountResponse>> getConversionFunnel() {
        return ResponseEntity.ok(reportService.getConversionFunnel());
    }

    @GetMapping("/export/leads.csv")
    @Operation(summary = "Export leads as CSV")
    public ResponseEntity<String> exportLeadsCsv() {
        return csvResponse(reportService.exportLeadsCsv(), "leads.csv");
    }

    @GetMapping("/export/bookings.csv")
    @Operation(summary = "Export bookings as CSV")
    public ResponseEntity<String> exportBookingsCsv() {
        return csvResponse(reportService.exportBookingsCsv(), "bookings.csv");
    }

    @GetMapping("/export/revenue.csv")
    @Operation(summary = "Export revenue trend as CSV")
    public ResponseEntity<String> exportRevenueCsv(@RequestParam(value = "months", defaultValue = "6") int months) {
        return csvResponse(reportService.exportRevenueCsv(months), "revenue.csv");
    }

    @GetMapping("/export/agents.csv")
    @Operation(summary = "Export agent leaderboard as CSV")
    public ResponseEntity<String> exportAgentsCsv() {
        return csvResponse(reportService.exportAgentsCsv(), "agents.csv");
    }

    private ResponseEntity<String> csvResponse(String csv, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(csv);
    }
}
