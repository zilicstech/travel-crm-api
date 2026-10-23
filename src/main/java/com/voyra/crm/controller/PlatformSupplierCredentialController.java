package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformSupplierCredentialRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.service.PlatformSupplierTestService;
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

/** Voyra's own supplier account (e.g. Tripjack), used by every agency's searches. Write-only: there is no reveal. */
@RestController
@RequestMapping("/api/platform/supplier-credentials")
@RequiredArgsConstructor
@Tag(name = "Platform - Supplier Credentials", description = "SUPER_ADMIN management of the platform's supplier credentials")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformSupplierCredentialController {

    private final PlatformSupplierCredentialService credentialService;
    private final PlatformSupplierTestService testService;

    @PutMapping
    @Operation(summary = "Set the platform credential for a supplier",
            description = "An empty apiKey keeps the stored key. Activating it deactivates any other for the provider.")
    public ResponseEntity<PlatformSupplierCredentialResponse> upsert(@Valid @RequestBody PlatformSupplierCredentialRequest request) {
        return ResponseEntity.ok(credentialService.upsert(request));
    }

    @GetMapping("/{provider}")
    @Operation(summary = "Get credential status", description = "The key is always masked.")
    public ResponseEntity<PlatformSupplierCredentialResponse> get(@PathVariable SupplierProvider provider) {
        return ResponseEntity.ok(credentialService.get(provider));
    }

    @PostMapping("/{provider}/test")
    @Operation(summary = "Run a live test search with the active credential", description = "DEL to BOM, 30 days out, one adult.")
    public ResponseEntity<SupplierTestResult> test(@PathVariable SupplierProvider provider) {
        return ResponseEntity.ok(testService.testCall(provider));
    }
}
