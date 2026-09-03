package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencySettingCreateRequest;
import com.voyra.crm.dto.AgencySettingResponse;
import com.voyra.crm.dto.AgencySettingUpdateRequest;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.service.AgencySettingService;
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

/**
 * Agency-editable configuration - lead sources, travel categories, document types, per-service
 * preferences. Agents may read (every screen that fills a lead or a service needs these lists)
 * but only the owner may add, rename or retire an option.
 */
@Slf4j
@RestController
@RequestMapping("/api/agency/settings")
@RequiredArgsConstructor
@Tag(name = "Agency Settings", description = "Agency-configurable lead sources, travel categories, document types and service preferences")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class AgencySettingController {

    private final AgencySettingService agencySettingService;

    @PostMapping
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Add an option", description = "Owner-only.")
    public ResponseEntity<AgencySettingResponse> create(@Valid @RequestBody AgencySettingCreateRequest request) {
        return ResponseEntity.ok(agencySettingService.create(request));
    }

    @GetMapping
    @Operation(summary = "List options of one kind",
            description = "serviceType is required to get a meaningful list when kind is SERVICE_PREFERENCE.")
    public ResponseEntity<List<AgencySettingResponse>> list(
            @RequestParam("kind") AgencySettingKind kind,
            @RequestParam(value = "serviceType", required = false) ServiceType serviceType) {
        return ResponseEntity.ok(agencySettingService.list(kind, serviceType));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Rename, retire or reorder an option", description = "Owner-only.")
    public ResponseEntity<AgencySettingResponse> update(@PathVariable String id,
                                                         @Valid @RequestBody AgencySettingUpdateRequest request) {
        return ResponseEntity.ok(agencySettingService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_OWNER')")
    @Operation(summary = "Remove an option outright", description = "Owner-only.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        agencySettingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
