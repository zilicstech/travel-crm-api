package com.voyra.crm.controller;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformBookingCancelRequest;
import com.voyra.crm.dto.PlatformBookingConfirmRequest;
import com.voyra.crm.dto.PlatformBookingResponse;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.service.booking.CancelPreview;
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
@RequestMapping("/api/platform-bookings")
@RequiredArgsConstructor
@Tag(name = "Platform bookings", description = "Book flights through Voyra's supplier account and pay from the agency wallet")
@PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT','ACCOUNTANT')")
public class PlatformBookingController {

    private final PlatformBookingService service;
    private final PlatformBookingQueryService queryService;

    @PostMapping("/flights/review")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Re-price a flight offer", description = "Creates a REVIEWING booking with the displayed fare to confirm.")
    public ResponseEntity<PlatformBookingReviewResponse> review(@Valid @RequestBody FlightReviewRequest request) {
        return ResponseEntity.ok(service.review(request));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Hold the wallet and book", description = "The same idempotencyKey returns the same booking.")
    public ResponseEntity<PlatformBookingResponse> confirm(@PathVariable String id, @Valid @RequestBody PlatformBookingConfirmRequest request) {
        return ResponseEntity.ok(queryService.view(service.confirm(id, request)));
    }

    @PostMapping("/{id}/abort")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Drop a booking that was never sent to the airline")
    public ResponseEntity<PlatformBookingResponse> abort(@PathVariable String id) {
        return ResponseEntity.ok(queryService.view(service.abort(id)));
    }

    @GetMapping("/{id}/cancel-quote")
    @Operation(summary = "What a cancellation would refund", description = "refundToBeConfirmed is true when the airline has not said.")
    public ResponseEntity<CancelPreview> cancelQuote(@PathVariable String id) {
        return ResponseEntity.ok(service.cancelQuote(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Cancel a ticketed booking", description = "The same idempotencyKey cancels once.")
    public ResponseEntity<PlatformBookingResponse> cancel(@PathVariable String id, @Valid @RequestBody PlatformBookingCancelRequest request) {
        return ResponseEntity.ok(queryService.view(service.cancel(id, request.getIdempotencyKey())));
    }

    @GetMapping
    @Operation(summary = "Bookings, newest first", description = "Agents see only their own; owner and accountant see the whole agency.")
    public ResponseEntity<PagedResponse<PlatformBookingResponse>> list(@RequestParam(value = "page", required = false) Integer page,
                                                                       @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(queryService.list(page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One booking with its plain-language history")
    public ResponseEntity<PlatformBookingResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(queryService.get(id));
    }
}
