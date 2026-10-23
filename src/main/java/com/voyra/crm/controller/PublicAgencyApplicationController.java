package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.dto.KycDocumentResponse;
import com.voyra.crm.dto.SimpleAckResponse;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.security.InquiryRateLimiter;
import com.voyra.crm.service.AgencyApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Unauthenticated agency sign-up (the /api/public/** permitAll surface); uploads and submit need the upload token. */
@RestController
@RequestMapping("/api/public/agency-applications")
@RequiredArgsConstructor
@Tag(name = "Public - Agency applications", description = "Agencies apply to join the platform without an account")
public class PublicAgencyApplicationController {

    private static final String TOKEN_HEADER = "X-Upload-Token";

    private final AgencyApplicationService agencyApplicationService;
    private final InquiryRateLimiter inquiryRateLimiter;

    @PostMapping
    @Operation(summary = "Start an agency application", description = "No auth required. Returns the application id "
            + "and a one-time upload token (valid 24 hours) for the document and submit calls.")
    @ApiResponse(responseCode = "429", description = "More than 5 requests a minute from one connection")
    public ResponseEntity<AgencyApplicationCreatedResponse> create(@Valid @RequestBody AgencyApplicationCreateRequest request,
                                                                   HttpServletRequest http) {
        inquiryRateLimiter.check(InquiryRateLimiter.callerOf(http));
        return ResponseEntity.status(HttpStatus.CREATED).body(agencyApplicationService.create(request));
    }

    @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
    @Operation(summary = "Upload a KYC document", description = "PDF/JPG/PNG/WEBP up to 5 MB, at most 20 per application. Needs the X-Upload-Token header.")
    public ResponseEntity<KycDocumentResponse> upload(@PathVariable String id,
                                                      @RequestHeader(TOKEN_HEADER) String uploadToken,
                                                      @RequestParam KycDocumentType type,
                                                      @RequestPart("file") MultipartFile file) {
        // Not rate-limited: bounded by the per-application document cap, and create (which issues the token) is limited.
        return ResponseEntity.ok(agencyApplicationService.attachDocument(id, uploadToken, type, file));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit the application for review", description = "Needs the X-Upload-Token header and a PAN document.")
    public ResponseEntity<SimpleAckResponse> submit(@PathVariable String id, @RequestHeader(TOKEN_HEADER) String uploadToken) {
        agencyApplicationService.submit(id, uploadToken);
        return ResponseEntity.ok(SimpleAckResponse.builder().success(true).message("Application submitted").build());
    }
}
