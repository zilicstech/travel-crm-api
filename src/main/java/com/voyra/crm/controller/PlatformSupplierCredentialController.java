package com.voyra.crm.controller;

import com.voyra.crm.dto.PlatformSupplierCreateRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.PlatformSupplierUpdateRequest;
import com.voyra.crm.dto.SupplierProductTerms;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.service.PlatformSupplierTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Voyra's own supplier accounts (e.g. Tripjack), used by every agency's searches and bookings. Write-only: there is no reveal. */
@RestController
@RequestMapping("/api/platform/supplier-credentials")
@RequiredArgsConstructor
@Tag(name = "Platform - Suppliers", description = "SUPER_ADMIN management of the platform's suppliers, keys and commission terms")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformSupplierCredentialController {

    private final PlatformSupplierCredentialService credentialService;
    private final PlatformSupplierTestService testService;

    @GetMapping
    @Operation(summary = "List suppliers", description = "Keys are always masked.")
    public ResponseEntity<List<PlatformSupplierCredentialResponse>> list() {
        return ResponseEntity.ok(credentialService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one supplier", description = "The key is always masked.")
    public ResponseEntity<PlatformSupplierCredentialResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(credentialService.get(id));
    }

    @PostMapping
    @Operation(summary = "Add a supplier", description = "Production only. 409 SUPPLIER_EXISTS when the integration already has a supplier.")
    public ResponseEntity<PlatformSupplierCredentialResponse> create(@Valid @RequestBody PlatformSupplierCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change name, key or active flag", description = "A blank apiKey keeps the stored key.")
    public ResponseEntity<PlatformSupplierCredentialResponse> update(@PathVariable String id,
                                                                     @Valid @RequestBody PlatformSupplierUpdateRequest request) {
        return ResponseEntity.ok(credentialService.update(id, request));
    }

    @PutMapping("/{id}/products")
    @Operation(summary = "Change commission terms per product", description = "Never refused for bookings in flight.")
    public ResponseEntity<PlatformSupplierCredentialResponse> updateProducts(@PathVariable String id,
                                                                             @RequestBody List<@Valid SupplierProductTerms> products) {
        return ResponseEntity.ok(credentialService.updateProducts(id, products));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Run a live test search with this supplier's key", description = "DEL to BOM, 30 days out, one adult.")
    public ResponseEntity<SupplierTestResult> test(@PathVariable String id) {
        return ResponseEntity.ok(testService.testCall(id));
    }
}
