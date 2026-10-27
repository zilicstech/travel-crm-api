package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencyApplicationResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.ApplicationApproveRequest;
import com.voyra.crm.dto.ApplicationDecisionRequest;
import com.voyra.crm.dto.KycDocumentDecisionRequest;
import com.voyra.crm.dto.KycDocumentResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.service.AgencyApplicationService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/platform/agency-applications")
@RequiredArgsConstructor
@Tag(name = "Platform - Agency applications", description = "SUPER_ADMIN review of agency KYC applications; approve provisions the agency")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencyApplicationController {

    private final AgencyApplicationService agencyApplicationService;

    @GetMapping
    @Operation(summary = "List applications, newest submission first",
            description = "?status= may repeat; without it lists SUBMITTED, UNDER_REVIEW and MORE_INFO. Always paged (page defaults to 0).")
    public ResponseEntity<PagedResponse<AgencyApplicationResponse>> list(
            @RequestParam(value = "status", required = false) List<ApplicationStatus> status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(agencyApplicationService.list(status, PageRequestUtil.resolve(page == null ? 0 : page, size)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One application with its KYC documents")
    public ResponseEntity<AgencyApplicationResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(agencyApplicationService.get(id));
    }

    @GetMapping("/{id}/documents/{docId}/file")
    @Operation(summary = "View a KYC document's file", description = "Every view is written to the platform audit log.")
    public ResponseEntity<Resource> openDocument(@PathVariable String id, @PathVariable String docId) {
        AgencyApplicationService.KycFileContent content = agencyApplicationService.openDocument(id, docId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(content.resource());
    }

    @PatchMapping("/{id}/documents/{docId}")
    @Operation(summary = "Verify or reject one KYC document")
    public ResponseEntity<KycDocumentResponse> decideDocument(@PathVariable String id, @PathVariable String docId,
                                                              @Valid @RequestBody KycDocumentDecisionRequest request) {
        return ResponseEntity.ok(agencyApplicationService.decideDocument(id, docId, request));
    }

    @PostMapping("/{id}/decision")
    @Operation(summary = "Move to UNDER_REVIEW, ask for more information, or reject",
            description = "MORE_INFO issues a fresh 7-day upload link, returned once as resumeUrl. REJECTED needs rejectionReason.")
    public ResponseEntity<AgencyApplicationResponse> decide(@PathVariable String id,
                                                            @Valid @RequestBody ApplicationDecisionRequest request) {
        return ResponseEntity.ok(agencyApplicationService.decide(id, request));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve and provision the agency",
            description = "Creates the agency, its schema and the owner login. Only from SUBMITTED, UNDER_REVIEW or MORE_INFO; a second call is 409.")
    public ResponseEntity<AgencyResponse> approve(@PathVariable String id, @Valid @RequestBody ApplicationApproveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agencyApplicationService.approve(id, request.getOwnerPassword()));
    }
}
