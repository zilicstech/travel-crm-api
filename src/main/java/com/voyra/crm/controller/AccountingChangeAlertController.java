package com.voyra.crm.controller;

import com.voyra.crm.dto.AccountingChangeAlertResponse;
import com.voyra.crm.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The accountant-facing queue of post-confirmation itinerary edits - FRD US-ACC-1.1. Payables-
 * shaped access, not agent-scoped, matching {@code SupplierInvoiceController}'s gate.
 */
@RestController
@RequestMapping("/api/accounts/change-alerts")
@RequiredArgsConstructor
@Tag(name = "Accounting Change Alerts", description = "Post-confirmation itinerary edits awaiting accountant review")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class AccountingChangeAlertController {

    private final BookingService bookingService;

    @GetMapping
    @Operation(summary = "Unacknowledged change alerts, newest first")
    public ResponseEntity<List<AccountingChangeAlertResponse>> list() {
        return ResponseEntity.ok(bookingService.listUnacknowledgedChangeAlerts());
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge a change alert")
    public ResponseEntity<Void> acknowledge(@PathVariable String id) {
        bookingService.acknowledgeChangeAlert(id);
        return ResponseEntity.noContent().build();
    }
}
