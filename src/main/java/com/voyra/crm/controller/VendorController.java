package com.voyra.crm.controller;

import com.voyra.crm.dto.VendorCreateRequest;
import com.voyra.crm.dto.VendorResponse;
import com.voyra.crm.dto.VendorStatusUpdateRequest;
import com.voyra.crm.dto.VendorUpdateRequest;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.service.VendorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Supplier master records - who the agency buys from. Owner-configures, everyone-reads. */
@Slf4j
@RestController
@RequestMapping("/api/vendors")
@RequiredArgsConstructor
@Tag(name = "Vendors", description = "Supplier master records")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class VendorController {

    private final VendorService vendorService;

    @PostMapping
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Add a vendor", description = "Owner-only.")
    public ResponseEntity<VendorResponse> create(@Valid @RequestBody VendorCreateRequest request) {
        return ResponseEntity.ok(vendorService.create(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "List vendors", description = "activeOnly defaults to true.")
    public ResponseEntity<List<VendorResponse>> list(
            @RequestParam(value = "serviceType", required = false) ServiceType serviceType,
            @RequestParam(value = "activeOnly", required = false, defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(vendorService.list(serviceType, activeOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "Get one vendor")
    public ResponseEntity<VendorResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(vendorService.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Edit a vendor", description = "Owner-only. Renaming re-syncs every booking/proposal/supplier-invoice snapshot.")
    public ResponseEntity<VendorResponse> update(@PathVariable String id, @Valid @RequestBody VendorUpdateRequest request) {
        return ResponseEntity.ok(vendorService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Activate or deactivate a vendor", description = "Owner-only.")
    public ResponseEntity<VendorResponse> updateStatus(@PathVariable String id, @Valid @RequestBody VendorStatusUpdateRequest request) {
        return ResponseEntity.ok(vendorService.updateStatus(id, request.getIsActive()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Deactivate a vendor", description = "Owner-only. There is no hard delete - this deactivates.")
    public ResponseEntity<Void> deactivate(@PathVariable String id) {
        vendorService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
