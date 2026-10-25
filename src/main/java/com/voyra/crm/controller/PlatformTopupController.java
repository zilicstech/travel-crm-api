package com.voyra.crm.controller;

import com.voyra.crm.dto.TopupApproveRequest;
import com.voyra.crm.dto.TopupRejectRequest;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.TopupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/platform/topups")
@RequiredArgsConstructor
@Tag(name = "Platform - Top-ups", description = "SUPER_ADMIN approval queue for bank-transfer top-ups")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformTopupController {

    private final TopupService service;

    @GetMapping
    @Operation(summary = "Top-ups across all agencies, newest first", description = "?status= filters, e.g. PENDING")
    public ResponseEntity<List<TopupResponse>> list(@RequestParam(value = "status", required = false) TopupStatus status) {
        return ResponseEntity.ok(service.listForPlatform(status));
    }

    @GetMapping("/{id}/proof")
    @Operation(summary = "View the payment proof", description = "Every view is written to the platform audit log.")
    public ResponseEntity<Resource> proof(@PathVariable String id) {
        TopupService.ProofContent content = service.openProof(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(content.resource());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve and credit the wallet", description = "Credits approvedAmount. A second call is 409.")
    public ResponseEntity<TopupResponse> approve(@PathVariable String id, @Valid @RequestBody TopupApproveRequest request) {
        return ResponseEntity.ok(service.approve(id, SecurityContextUtil.getCurrentUserOrThrow().userId(), request));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject with a reason; posts nothing", description = "A second call is 409.")
    public ResponseEntity<TopupResponse> reject(@PathVariable String id, @Valid @RequestBody TopupRejectRequest request) {
        return ResponseEntity.ok(service.reject(id, SecurityContextUtil.getCurrentUserOrThrow().userId(), request));
    }
}
