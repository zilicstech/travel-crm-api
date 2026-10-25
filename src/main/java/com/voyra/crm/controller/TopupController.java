package com.voyra.crm.controller;

import com.voyra.crm.dto.BankTopupCreateRequest;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.TopupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wallet/topups")
@RequiredArgsConstructor
@Tag(name = "Wallet top-ups", description = "Agency wallet top-ups: owner requests, accountant reads")
@PreAuthorize("hasAnyRole('AGENCY_OWNER','ACCOUNTANT')")
public class TopupController {

    private final TopupService service;

    @PostMapping(value = "/bank", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Request a bank-transfer top-up with UTR and proof",
            description = "The wallet must be open. The UTR is unique across the platform, ignoring case and spaces.")
    public ResponseEntity<TopupResponse> bank(@Valid @ModelAttribute BankTopupCreateRequest request) {
        CustomUserPrincipal me = SecurityContextUtil.getCurrentUserOrThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBank(me.tenantId(), me.userId(), me.username(), request));
    }

    @GetMapping
    @Operation(summary = "This agency's top-ups, newest first")
    public ResponseEntity<List<TopupResponse>> list() {
        return ResponseEntity.ok(service.listForAgency(SecurityContextUtil.getCurrentUserOrThrow().tenantId()));
    }
}
