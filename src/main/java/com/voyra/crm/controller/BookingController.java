package com.voyra.crm.controller;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingDeadlineUpdateRequest;
import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingRefundUpdateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.FeedbackLinkService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Shared between AGENCY_OWNER and AGENT - same rules for both, scope resolved per caller (like CustomerController). */
@Slf4j
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Flight/Hotel/Package/Visa bookings - shared, scoped per caller")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class BookingController {

    private final BookingService bookingService;
    private final FeedbackLinkService feedbackLinkService;

    @PostMapping
    @Operation(summary = "Create a booking", description = "Profit is always server-computed from sellingPrice - netCost.")
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody BookingCreateRequest request) {
        return ResponseEntity.ok(bookingService.createBooking(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "List bookings",
            description = "Agents see only their own bookings; Owners and Accountants see the whole agency "
                    + "(the accounts module bills against any booking). Supply ?page= for a paged envelope; "
                    + "omit it for the full list as a plain array.")
    public ResponseEntity<Object> listBookings(
            @RequestParam(value = "type", required = false) BookingType type,
            @RequestParam(value = "status", required = false) BookingStatus status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? bookingService.listBookings(type, status)
                : bookingService.listBookings(type, status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "Booking detail")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String id) {
        return ResponseEntity.ok(bookingService.getBooking(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update booking details")
    public ResponseEntity<BookingResponse> updateBooking(@PathVariable String id,
                                                          @Valid @RequestBody BookingUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updateBooking(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update booking status", description = "cancelReason is mandatory when bookingStatus is CANCELLED.")
    public ResponseEntity<BookingResponse> updateStatus(@PathVariable String id,
                                                         @Valid @RequestBody BookingStatusUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updateStatus(id, request));
    }

    @PatchMapping("/{id}/payment-status")
    @Operation(summary = "Update payment status")
    public ResponseEntity<BookingResponse> updatePaymentStatus(@PathVariable String id,
                                                                @Valid @RequestBody BookingPaymentStatusUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updatePaymentStatus(id, request));
    }

    @PatchMapping("/{id}/refund")
    @Operation(summary = "Update a cancelled booking's refund state", description = "Only valid once bookingStatus is CANCELLED.")
    public ResponseEntity<BookingResponse> updateRefund(@PathVariable String id,
                                                         @Valid @RequestBody BookingRefundUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updateRefund(id, request));
    }

    @PatchMapping("/{id}/deadlines")
    @Operation(summary = "Set ticketing/cancellation deadlines")
    public ResponseEntity<BookingResponse> updateDeadlines(@PathVariable String id,
                                                            @Valid @RequestBody BookingDeadlineUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updateDeadlines(id, request));
    }

    @PostMapping("/{id}/feedback-link")
    @Operation(summary = "Generate (or reuse) the public shareable feedback link")
    public ResponseEntity<com.voyra.crm.dto.FeedbackLinkResponse> generateFeedbackLink(@PathVariable String id) {
        return ResponseEntity.ok(feedbackLinkService.generateLink(id));
    }
}
