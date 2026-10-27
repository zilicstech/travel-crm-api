package com.voyra.crm.controller;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformAdminBookingResponse;
import com.voyra.crm.dto.PlatformBookingResolveCancelRequest;
import com.voyra.crm.dto.PlatformBookingResolveRequest;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.service.booking.PlatformBookingQueryService;
import com.voyra.crm.service.booking.PlatformBookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/platform/bookings")
@RequiredArgsConstructor
@Tag(name = "Platform - Bookings", description = "SUPER_ADMIN view of every booking made through Voyra, and manual resolution")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAdminBookingController {

    private final PlatformBookingService service;
    private final PlatformBookingQueryService queryService;

    @GetMapping
    @Operation(summary = "Bookings across all agencies, newest first", description = "?status= and ?tenantId= filter.")
    public ResponseEntity<PagedResponse<PlatformAdminBookingResponse>> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "status", required = false) PlatformBookingStatus status,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ResponseEntity.ok(queryService.adminList(page, size, status, tenantId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One booking with money and the full event trail")
    public ResponseEntity<PlatformAdminBookingResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(queryService.adminGet(id));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Settle a BOOKING or UNKNOWN booking as TICKETED or FAILED", description = "Audited; the note is required.")
    public ResponseEntity<PlatformAdminBookingResponse> resolve(@PathVariable String id, @Valid @RequestBody PlatformBookingResolveRequest request) {
        return ResponseEntity.ok(queryService.adminView(
                service.resolve(id, request.getOutcome(), request.getPnr(), request.getTicketNumbers(), request.getNote())));
    }

    @PostMapping("/{id}/resolve-cancel")
    @Operation(summary = "Settle a CANCELLING booking the supplier never answered", description = "Audited; the note is required.")
    public ResponseEntity<PlatformAdminBookingResponse> resolveCancel(@PathVariable String id,
                                                                      @Valid @RequestBody PlatformBookingResolveCancelRequest request) {
        return ResponseEntity.ok(queryService.adminView(
                service.resolveCancel(id, request.getCancelled(), request.getRefund(), request.getNote())));
    }
}
