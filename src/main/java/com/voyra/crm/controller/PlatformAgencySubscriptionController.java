package com.voyra.crm.controller;

import com.voyra.crm.dto.SubscriptionAdminResponse;
import com.voyra.crm.dto.SubscriptionUpdateRequest;
import com.voyra.crm.dto.SubscriptionWaiveRequest;
import com.voyra.crm.dto.TrialExtendRequest;
import com.voyra.crm.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/agencies/{tenantId}/subscription")
@RequiredArgsConstructor
@Tag(name = "Platform - Agency subscription", description = "SUPER_ADMIN plan, SKU, seat, waiver and trial changes")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencySubscriptionController {

    private final SubscriptionService service;

    @GetMapping
    @Operation(summary = "An agency's subscription, seat usage and waiver reason")
    public ResponseEntity<SubscriptionAdminResponse> get(@PathVariable String tenantId) {
        return ResponseEntity.ok(service.adminView(tenantId));
    }

    @PutMapping
    @Operation(summary = "Change plan, SKUs, extra seats and billing cycle")
    public ResponseEntity<SubscriptionAdminResponse> update(@PathVariable String tenantId,
                                                            @Valid @RequestBody SubscriptionUpdateRequest request) {
        return ResponseEntity.ok(service.update(tenantId, request));
    }

    @PostMapping("/waive")
    @Operation(summary = "Waive the fee until a date, or without expiry")
    public ResponseEntity<SubscriptionAdminResponse> waive(@PathVariable String tenantId,
                                                           @Valid @RequestBody SubscriptionWaiveRequest request) {
        return ResponseEntity.ok(service.waive(tenantId, request));
    }

    @PostMapping("/extend-trial")
    @Operation(summary = "Extend or restart the Suite trial")
    public ResponseEntity<SubscriptionAdminResponse> extendTrial(@PathVariable String tenantId,
                                                                 @Valid @RequestBody TrialExtendRequest request) {
        return ResponseEntity.ok(service.extendTrial(tenantId, request));
    }
}
