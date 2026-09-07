package com.voyra.crm.controller;

import com.voyra.crm.dto.SupplierCredentialResponse;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.dto.SupplierCredentialUpsertRequest;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.service.SupplierCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only credentials for an external supplier search integration (e.g. Tripjack), one
 * row per provider. Agents never reach this - they only ever consume the search endpoints
 * these credentials feed, never the credentials themselves.
 */
@Slf4j
@RestController
@RequestMapping("/api/supplier-credentials")
@RequiredArgsConstructor
@Tag(name = "Supplier Credentials", description = "Owner-only external supplier credentials (e.g. Tripjack)")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class SupplierCredentialController {

    private final SupplierCredentialService supplierCredentialService;

    @PutMapping
    @Operation(summary = "Create or update the agency's credentials for a supplier",
            description = "An empty apiKey leaves the stored key unchanged.")
    public ResponseEntity<SupplierCredentialResponse> upsert(@Valid @RequestBody SupplierCredentialUpsertRequest request) {
        return ResponseEntity.ok(supplierCredentialService.upsert(request));
    }

    @GetMapping("/{provider}")
    @Operation(summary = "Get credential status", description = "The key is always masked here.")
    public ResponseEntity<SupplierCredentialResponse> get(@PathVariable SupplierProvider provider) {
        return ResponseEntity.ok(supplierCredentialService.get(provider));
    }

    @GetMapping("/{provider}/reveal")
    @Operation(summary = "Retrieve the decrypted credentials", description = "Every retrieval is logged.")
    public ResponseEntity<SupplierCredentialRevealResponse> reveal(@PathVariable SupplierProvider provider) {
        return ResponseEntity.ok(supplierCredentialService.reveal(provider));
    }
}
