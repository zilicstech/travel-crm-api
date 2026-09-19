package com.voyra.crm.controller;

import com.voyra.crm.dto.ActiveStatusUpdateRequest;
import com.voyra.crm.dto.TaxRateConfigCreateRequest;
import com.voyra.crm.dto.TaxRateConfigResponse;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.service.TaxConfigService;
import com.voyra.crm.util.GstStateCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** GST/TCS slab configuration. Agency Owner and Accountant only - agents never see tax settings. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/tax-config")
@RequiredArgsConstructor
@Tag(name = "Accounts - Tax Config", description = "GST/TCS slab configuration")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class TaxConfigController {

    private final TaxConfigService taxConfigService;

    @GetMapping
    @Operation(summary = "List tax rate configs", description = "Optionally filter by taxKind (GST or TCS).")
    public ResponseEntity<List<TaxRateConfigResponse>> list(
            @RequestParam(value = "taxKind", required = false) TaxKind taxKind) {
        return ResponseEntity.ok(taxConfigService.list(taxKind));
    }

    @PostMapping
    @Operation(summary = "Add a new GST or TCS slab")
    public ResponseEntity<TaxRateConfigResponse> create(@Valid @RequestBody TaxRateConfigCreateRequest request) {
        return ResponseEntity.ok(taxConfigService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a slab", description = "Closes the existing row and inserts the replacement as a new version - a rate is never updated in place.")
    public ResponseEntity<TaxRateConfigResponse> replace(@PathVariable String id, @Valid @RequestBody TaxRateConfigCreateRequest request) {
        return ResponseEntity.ok(taxConfigService.replace(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a slab")
    public ResponseEntity<TaxRateConfigResponse> updateStatus(@PathVariable String id, @Valid @RequestBody ActiveStatusUpdateRequest request) {
        return ResponseEntity.ok(taxConfigService.updateStatus(id, request.getIsActive()));
    }

    @GetMapping("/states")
    @Operation(summary = "India GST state/UT codes", description = "Fixed reference list for the place-of-supply picker.")
    public ResponseEntity<Map<String, String>> states() {
        return ResponseEntity.ok(GstStateCode.all());
    }
}
