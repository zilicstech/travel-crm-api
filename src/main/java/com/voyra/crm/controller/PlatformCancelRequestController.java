package com.voyra.crm.controller;

import com.voyra.crm.util.ProofFiles;
import com.voyra.crm.dto.AdminCancelRequestResponse;
import com.voyra.crm.dto.CancelRequestCompleteRequest;
import com.voyra.crm.dto.CancelRequestRejectRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/cancel-requests")
@RequiredArgsConstructor
@Tag(name = "Platform - Cancellation requests", description = "SUPER_ADMIN queue of agencies' cancellation requests: review, cancel at the supplier by hand, complete")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformCancelRequestController {

    private final PlatformCancelRequestService service;

    @GetMapping
    @Operation(summary = "Requests by status", description = "No status: the active ones (open, in progress, completing), oldest first.")
    public ResponseEntity<PagedResponse<AdminCancelRequestResponse>> list(@RequestParam(value = "status", required = false) CancelRequestStatus status,
                                                                          @RequestParam(value = "page", required = false) Integer page,
                                                                          @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(service.adminList(status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One request with the full booking and the complete form's prefills")
    public ResponseEntity<AdminCancelRequestResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(service.adminGet(id));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Pick the request up", description = "The agency can no longer withdraw it.")
    public ResponseEntity<AdminCancelRequestResponse> start(@PathVariable String id) {
        return ResponseEntity.ok(service.start(id));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Decline the request with a note for the agency")
    public ResponseEntity<AdminCancelRequestResponse> reject(@PathVariable String id, @Valid @RequestBody CancelRequestRejectRequest request) {
        return ResponseEntity.ok(service.reject(id, request.note()));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Record what was cancelled at the supplier and credit the agency",
            description = "Refund = supplier refund less Voyra's fee, capped; 409 EXCEEDS_SHARE above the chosen share unless confirmed; "
                    + "409 FIGURES_LOCKED when retried with other figures.")
    public ResponseEntity<AdminCancelRequestResponse> complete(@PathVariable String id, @Valid @RequestBody CancelRequestCompleteRequest request) {
        return ResponseEntity.ok(service.complete(id, request));
    }

    @GetMapping("/{id}/files/{fileId}")
    @Operation(summary = "A proof file the agency attached", description = "Every view is written to the platform audit log.")
    public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String fileId) {
        return ProofFiles.inline(service.adminFile(id, fileId));
    }
}
