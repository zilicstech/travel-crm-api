package com.voyra.crm.controller;

import com.voyra.crm.dto.ExchangeRateResponse;
import com.voyra.crm.service.ExchangeRateService;
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

/** Read-only view of the platform's reference rates for agency users filling a foreign-currency form. */
@RestController
@RequestMapping("/api/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Exchange Rates", description = "Latest stored reference rate for a currency pair")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class ExchangeRateLookupController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping("/latest")
    @Operation(summary = "Most recent stored rate on or before a date (default today); 204 when none is stored")
    public ResponseEntity<ExchangeRateResponse> latest(
            @RequestParam String base,
            @RequestParam(defaultValue = "INR") String quote,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return exchangeRateService.latest(base, quote, date != null ? date : LocalDate.now())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
