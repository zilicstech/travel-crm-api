package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencySubscriptionResponse;
import com.voyra.crm.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agency/subscription")
@RequiredArgsConstructor
@Tag(name = "Owner - Subscription", description = "The agency's plan, seats, period and invoices")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class AgencySubscriptionController {

    private final SubscriptionService service;

    @GetMapping
    @Operation(summary = "Plan, SKUs, seats used/total, status, period and invoices")
    public ResponseEntity<AgencySubscriptionResponse> get() {
        return ResponseEntity.ok(service.agencyView());
    }
}
