package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.service.AgencyProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only: the signed-in owner's own agency profile (GST, address, currency, default commission, logo). */
@Slf4j
@RestController
@RequestMapping("/api/agency/profile")
@RequiredArgsConstructor
@Tag(name = "Agency Profile", description = "The signed-in owner's own agency profile")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class AgencyProfileController {

    private final AgencyProfileService agencyProfileService;

    @GetMapping
    @Operation(summary = "Get my agency's profile")
    public ResponseEntity<AgencyProfileResponse> getProfile() {
        return ResponseEntity.ok(agencyProfileService.getProfile());
    }

    @PutMapping
    @Operation(summary = "Update my agency's profile")
    public ResponseEntity<AgencyProfileResponse> updateProfile(@Valid @RequestBody AgencyProfileUpdateRequest request) {
        return ResponseEntity.ok(agencyProfileService.updateProfile(request));
    }
}
