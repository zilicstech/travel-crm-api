package com.voyra.crm.controller;

import com.voyra.crm.dto.ActiveStatusUpdateRequest;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.service.AgencyService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/platform/agencies")
@RequiredArgsConstructor
@Tag(name = "Platform - Agencies", description = "SUPER_ADMIN management of Agencies (tenants)")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencyController {

    private final AgencyService agencyService;

    @PostMapping
    @Operation(summary = "Onboard a new Agency", description = "Creates the Agency, the Owner login, and provisions the tenant schema.")
    public ResponseEntity<AgencyCreateResponse> createAgency(@Valid @RequestBody AgencyCreateRequest request) {
        return ResponseEntity.ok(agencyService.createAgency(request));
    }

    @GetMapping
    @Operation(summary = "List all Agencies")
    public ResponseEntity<List<AgencyResponse>> listAgencies() {
        return ResponseEntity.ok(agencyService.listAgencies());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Agency detail", description = "Includes a cross-tenant revenue read.")
    public ResponseEntity<AgencyResponse> getAgency(@PathVariable String id) {
        return ResponseEntity.ok(agencyService.getAgency(id));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate an Agency")
    public ResponseEntity<AgencyResponse> updateStatus(@PathVariable String id,
                                                        @Valid @RequestBody ActiveStatusUpdateRequest request) {
        return ResponseEntity.ok(agencyService.updateStatus(id, request.getIsActive()));
    }

    @GetMapping("/{id}/credentials")
    @Operation(summary = "Retrieve the Owner's login credentials", description = "Every retrieval is logged.")
    public ResponseEntity<CredentialsResponse> getCredentials(@PathVariable String id) {
        return ResponseEntity.ok(agencyService.getCredentials(id));
    }
}
