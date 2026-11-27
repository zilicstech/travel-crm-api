package com.voyra.crm.controller;

import com.voyra.crm.dto.CancellationDetailResponse;
import com.voyra.crm.dto.CancellationFollowUpRequest;
import com.voyra.crm.dto.CancellationFollowUpResponse;
import com.voyra.crm.dto.CancellationFollowUpUpdateRequest;
import com.voyra.crm.dto.CancellationRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.service.CancellationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

@RestController
@RequestMapping("/api/cancellations")
@RequiredArgsConstructor
@Tag(name = "Cancellations", description = "Every booking that is cancelled, being cancelled or scheduled to be, with follow-ups")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
public class CancellationController {

    private final CancellationService cancellationService;

    @GetMapping
    @Operation(summary = "The Cancellations tab", description = "OPEN: still needs someone; DUE: due today or earlier; ALL: every cancellation")
    public ResponseEntity<PagedResponse<CancellationRowResponse>> list(
            @RequestParam(defaultValue = "OPEN") CancellationService.View view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(cancellationService.list(view, q, assignedTo, PageRequest.of(page, size)));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "One booking's cancellation: status, refunds, Voyra requests, follow-ups and activity")
    public ResponseEntity<CancellationDetailResponse> detail(@PathVariable String bookingId) {
        return ResponseEntity.ok(cancellationService.detail(bookingId));
    }

    @PostMapping("/{bookingId}/follow-ups")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
    @Operation(summary = "Schedule a cancellation for later, or add a follow-up")
    public ResponseEntity<CancellationFollowUpResponse> addFollowUp(@PathVariable String bookingId,
                                                                    @Valid @RequestBody CancellationFollowUpRequest request) {
        return ResponseEntity.ok(cancellationService.addFollowUp(bookingId, request));
    }

    @PatchMapping("/{bookingId}/follow-ups/{followUpId}")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
    @Operation(summary = "Move, reassign or re-note an open follow-up")
    public ResponseEntity<CancellationFollowUpResponse> updateFollowUp(@PathVariable String bookingId, @PathVariable String followUpId,
                                                                       @Valid @RequestBody CancellationFollowUpUpdateRequest request) {
        return ResponseEntity.ok(cancellationService.updateFollowUp(bookingId, followUpId, request));
    }

    @PatchMapping("/{bookingId}/follow-ups/{followUpId}/done")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
    @Operation(summary = "Mark a follow-up done", description = "Done, never deleted; a scheduled cancellation closed without a cancel means the booking was kept")
    public ResponseEntity<CancellationFollowUpResponse> completeFollowUp(@PathVariable String bookingId, @PathVariable String followUpId) {
        return ResponseEntity.ok(cancellationService.completeFollowUp(bookingId, followUpId));
    }

    @PostMapping("/{bookingId}/refund-processed")
    @Operation(summary = "Mark a cancelled booking's refund processed", description = "It leaves the Open list; owner, an agent who can open it, or the accountant")
    public ResponseEntity<CancellationRowResponse> markRefundProcessed(@PathVariable String bookingId) {
        return ResponseEntity.ok(cancellationService.setRefundProcessed(bookingId, true));
    }

    @DeleteMapping("/{bookingId}/refund-processed")
    @Operation(summary = "Reopen a cancelled booking's refund", description = "It comes back on the Open list")
    public ResponseEntity<CancellationRowResponse> reopenRefund(@PathVariable String bookingId) {
        return ResponseEntity.ok(cancellationService.setRefundProcessed(bookingId, false));
    }
}
