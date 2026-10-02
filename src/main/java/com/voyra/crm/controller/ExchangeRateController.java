package com.voyra.crm.controller;

import com.voyra.crm.dto.ExchangeRateRequest;
import com.voyra.crm.dto.ExchangeRateResponse;
import com.voyra.crm.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide (not per-agency) reference rates - manual entry until a live feed exists. */
@RestController
@RequestMapping("/api/platform/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Platform - Exchange Rates", description = "Daily reference rates shared by every tenant")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @PostMapping
    @Operation(summary = "Record or correct one day's rate for a currency pair")
    public ResponseEntity<ExchangeRateResponse> record(@Valid @RequestBody ExchangeRateRequest request) {
        return ResponseEntity.ok(exchangeRateService.record(request));
    }
}
