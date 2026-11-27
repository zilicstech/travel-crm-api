package com.voyra.crm.controller;

import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.PassengerCancelRequest;
import com.voyra.crm.service.PassengerCancellationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cancels some travellers of a flight ticket while the booking stays live for the rest (client 27 Nov). */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Cancel some passengers of a flight")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class PassengerCancellationController {

    private final PassengerCancellationService service;

    @PostMapping("/{id}/cancel-passengers")
    @Operation(summary = "Cancel some travellers of a flight ticket, keeping the booking for the rest",
            description = "Before the booking is invoiced (409 ALREADY_INVOICED): its ticket's cost drops by the purchase share less the "
                    + "airline and supplier charges, its price by the selling share less every charge; no GST on the charges")
    public ResponseEntity<BookingResponse> cancelPassengers(@PathVariable String id, @Valid @RequestBody PassengerCancelRequest request) {
        return ResponseEntity.ok(service.cancelPassengers(id, request));
    }
}
