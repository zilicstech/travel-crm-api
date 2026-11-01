package com.voyra.crm.controller;

import com.voyra.crm.dto.PlanPricesRequest;
import com.voyra.crm.dto.PlanPricesResponse;
import com.voyra.crm.service.PlatformPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/plans")
@RequiredArgsConstructor
@Tag(name = "Platform - Plans", description = "SUPER_ADMIN monthly prices of the Lite and Premium plans and the extra seat")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformPlanController {

    private final PlatformPlanService service;

    @GetMapping
    @Operation(summary = "Current Lite, Premium and extra-seat monthly prices")
    public ResponseEntity<PlanPricesResponse> get() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping
    @Operation(summary = "Set the Lite, Premium and extra-seat monthly prices")
    public ResponseEntity<PlanPricesResponse> set(@Valid @RequestBody PlanPricesRequest request) {
        return ResponseEntity.ok(service.set(request));
    }
}
