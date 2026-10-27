package com.voyra.crm.controller;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.WalletAdjustRequest;
import com.voyra.crm.dto.WalletCloseRequest;
import com.voyra.crm.dto.WalletEntryResponse;
import com.voyra.crm.dto.WalletSummaryResponse;
import com.voyra.crm.dto.WalletTestFlagRequest;
import com.voyra.crm.service.WalletQueryService;
import com.voyra.crm.util.PageRequestUtil;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/wallets/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Platform - Wallets", description = "SUPER_ADMIN view, adjust, close and test-flag an agency wallet")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformWalletController {

    private final WalletQueryService service;

    @GetMapping
    @Operation(summary = "An agency's wallet summary; 404 when it has none")
    public ResponseEntity<WalletSummaryResponse> summary(@PathVariable String tenantId) {
        return ResponseEntity.ok(service.summary(tenantId));
    }

    @GetMapping("/entries")
    @Operation(summary = "An agency's statement, newest first")
    public ResponseEntity<PagedResponse<WalletEntryResponse>> entries(@PathVariable String tenantId,
                                                                      @RequestParam(value = "page", required = false) Integer page,
                                                                      @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(service.entries(tenantId, PageRequestUtil.resolve(page == null ? 0 : page, size)));
    }

    @PostMapping("/adjust")
    @Operation(summary = "Signed correction; the same requestId posts once")
    public ResponseEntity<WalletEntryResponse> adjust(@PathVariable String tenantId, @Valid @RequestBody WalletAdjustRequest request) {
        return ResponseEntity.ok(service.adjust(tenantId, request));
    }

    @PostMapping("/close")
    @Operation(summary = "Pay the balance out by bank transfer and close the wallet")
    public ResponseEntity<WalletEntryResponse> close(@PathVariable String tenantId, @Valid @RequestBody WalletCloseRequest request) {
        return ResponseEntity.ok(service.close(tenantId, request.getUtr()));
    }

    @PutMapping("/test")
    @Operation(summary = "Mark or unmark a test wallet")
    public ResponseEntity<Void> test(@PathVariable String tenantId, @Valid @RequestBody WalletTestFlagRequest request) {
        service.setTest(tenantId, request.getIsTest());
        return ResponseEntity.ok().build();
    }
}
