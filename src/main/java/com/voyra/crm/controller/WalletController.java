package com.voyra.crm.controller;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.WalletEntryResponse;
import com.voyra.crm.dto.WalletSummaryResponse;
import com.voyra.crm.dto.WalletThresholdRequest;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.WalletQueryService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "The agency's own prepaid wallet: owner manages, accountant reads")
@PreAuthorize("hasAnyRole('AGENCY_OWNER','ACCOUNTANT')")
public class WalletController {

    private final WalletQueryService service;

    @GetMapping
    @Operation(summary = "Balance, held, available, due receivables and pending top-ups")
    public ResponseEntity<WalletSummaryResponse> summary() {
        return ResponseEntity.ok(service.summary(tenantId()));
    }

    @GetMapping("/entries")
    @Operation(summary = "Statement, newest first (page defaults to 0)")
    public ResponseEntity<PagedResponse<WalletEntryResponse>> entries(@RequestParam(value = "page", required = false) Integer page,
                                                                      @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(service.entries(tenantId(), PageRequestUtil.resolve(page == null ? 0 : page, size)));
    }

    @GetMapping("/entries.csv")
    @Operation(summary = "Every statement entry as CSV")
    public ResponseEntity<String> entriesCsv() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("wallet-statement.csv", StandardCharsets.UTF_8).build().toString())
                .body(service.csv(tenantId()));
    }

    @PutMapping("/threshold")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Set or clear the low-balance alert threshold")
    public ResponseEntity<Void> threshold(@Valid @RequestBody WalletThresholdRequest request) {
        service.setThreshold(tenantId(), request.getLowBalanceThreshold());
        return ResponseEntity.ok().build();
    }

    private static String tenantId() {
        return SecurityContextUtil.getCurrentUserOrThrow().tenantId();
    }
}
