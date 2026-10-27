package com.voyra.crm.controller;

import com.voyra.crm.dto.BankTopupCreateRequest;
import com.voyra.crm.dto.GatewayTopupCreateRequest;
import com.voyra.crm.dto.GatewayTopupResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping("/gateway")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Start an online top-up",
            description = "Creates a gateway order and a PENDING top-up; the browser opens checkout with the returned order id "
                    + "and key. The wallet is credited only by the signed webhook, never by the browser.")
    public ResponseEntity<GatewayTopupResponse> gateway(@Valid @RequestBody GatewayTopupCreateRequest request) {
        CustomUserPrincipal me = SecurityContextUtil.getCurrentUserOrThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGateway(me.tenantId(), me.userId(), me.username(), request));
    }

    @PostMapping("/{id}/simulate")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Simulate the gateway paying an online top-up (stub provider only)",
            description = "404 unless app.payments.provider=stub. Runs a payment.captured event through the real webhook handler.")
    public ResponseEntity<TopupResponse> simulate(@PathVariable String id) {
        return ResponseEntity.ok(service.simulate(SecurityContextUtil.getCurrentUserOrThrow().tenantId(), id));
    }

    @GetMapping
    @Operation(summary = "This agency's top-ups, newest first")
    public ResponseEntity<List<TopupResponse>> list() {
        return ResponseEntity.ok(service.listForAgency(SecurityContextUtil.getCurrentUserOrThrow().tenantId()));
    }
}
