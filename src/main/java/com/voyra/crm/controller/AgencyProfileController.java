package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.service.AgencyProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Owner-only (the logo image itself is readable by all agency staff): the owner's own agency profile (GST, address, currency, default commission, logo). */
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

    @PutMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace my agency's logo", description = "PNG, JPG or WEBP (checked by content), at most 1 MB.")
    public ResponseEntity<AgencyProfileResponse> uploadLogo(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(agencyProfileService.uploadLogo(file));
    }

    @DeleteMapping("/logo")
    @Operation(summary = "Remove my agency's logo")
    public ResponseEntity<AgencyProfileResponse> removeLogo() {
        return ResponseEntity.ok(agencyProfileService.removeLogo());
    }

    @GetMapping("/logo")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT','ACCOUNTANT')")
    @Operation(summary = "My agency's logo image", description = "Any user of the agency; 404 when there is none.")
    public ResponseEntity<Resource> logo() {
        AgencyProfileService.Logo logo = agencyProfileService.logo();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(logo.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(logo.resource());
    }
}
