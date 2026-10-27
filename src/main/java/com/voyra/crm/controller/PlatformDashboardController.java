package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformDashboardResponse;
import com.voyra.crm.service.PlatformDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/dashboard")
@RequiredArgsConstructor
@Tag(name = "Platform - Dashboard", description = "SUPER_ADMIN platform home tiles")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformDashboardController {

    private final PlatformDashboardService service;

    @GetMapping
    @Operation(summary = "Platform home tiles", description = "GMV and Voyra net over the last 30 IST days (live bookings, as on the P&L), "
            + "wallet float without test wallets, and the counts waiting for an admin.")
    public ResponseEntity<PlatformDashboardResponse> dashboard() {
        return ResponseEntity.ok(service.dashboard());
    }
}
