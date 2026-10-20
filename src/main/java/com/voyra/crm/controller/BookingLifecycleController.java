package com.voyra.crm.controller;

import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.service.BookingLifecycleService;
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
import org.springframework.web.bind.annotation.RestController;

/** The booking-management lifecycle: one read for every step, and the money steps as one-click actions. */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Booking lifecycle", description = "Create -> voucher -> invoice -> payment -> supplier paid -> refunds, per booking")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class BookingLifecycleController {

    private final BookingLifecycleService bookingLifecycleService;

    @GetMapping("/{id}/lifecycle")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "The booking's lifecycle", description = "Each step's state, the money records behind them and the actions this caller may take. Supplier figures are omitted for agents.")
    public ResponseEntity<BookingLifecycleResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(bookingLifecycleService.getLifecycle(id));
    }

    @PostMapping("/{id}/payments")
    @Operation(summary = "Record a payment received", description = "Settles the invoice, or before one exists is held on the client's account.")
    public ResponseEntity<BookingLifecycleResponse> recordPayment(@PathVariable String id, @Valid @RequestBody BookingPaymentRequest request) {
        return ResponseEntity.ok(bookingLifecycleService.recordPayment(id, request));
    }
}
