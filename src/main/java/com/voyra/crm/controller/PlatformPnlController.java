package com.voyra.crm.controller;

import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.service.PlatformPnlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/platform/pnl")
@RequiredArgsConstructor
@Tag(name = "Platform - P&L", description = "SUPER_ADMIN platform profit and loss over bookings made through Voyra")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformPnlController {

    private final PlatformPnlService service;

    @GetMapping
    @Operation(summary = "P&L over bookings ticketed between two IST days (inclusive)",
            description = "Totals plus one row per agency and per product. ?product=, ?tenantId= and ?environment= narrow it.")
    public ResponseEntity<PnlResponse> pnl(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "product", required = false) PricingProduct product,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "environment", required = false) SupplierEnvironment environment) {
        return ResponseEntity.ok(service.pnl(from, to, product, tenantId, environment));
    }
}
