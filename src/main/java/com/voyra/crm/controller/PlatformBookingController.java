package com.voyra.crm.controller;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.HotelReviewRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformBookingCancelRequest;
import com.voyra.crm.dto.PlatformBookingConfirmRequest;
import com.voyra.crm.dto.PlatformBookingResponse;
import com.voyra.crm.dto.PlatformBookingSummaryResponse;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.dto.TicketCreateRequest;
import com.voyra.crm.dto.TicketDownloadResponse;
import com.voyra.crm.service.booking.CancelPreview;
import com.voyra.crm.service.booking.PlatformBookingQueryService;
import com.voyra.crm.service.booking.PlatformBookingService;
import com.voyra.crm.service.booking.PlatformBookingTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/platform-bookings")
@RequiredArgsConstructor
@Tag(name = "Platform bookings", description = "Book flights and hotels through Voyra's supplier account and pay from the agency wallet")
@PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT','ACCOUNTANT')")
public class PlatformBookingController {

    private final PlatformBookingService service;
    private final PlatformBookingQueryService queryService;
    private final PlatformBookingTicketService ticketService;

    @PostMapping("/flights/review")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Re-price a flight offer", description = "Creates a REVIEWING booking with the displayed fare to confirm.")
    public ResponseEntity<PlatformBookingReviewResponse> review(@Valid @RequestBody FlightReviewRequest request) {
        return ResponseEntity.ok(service.review(request));
    }

    @PostMapping("/hotels/review")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Re-price a hotel room option", description = "Creates a REVIEWING hotel booking with the displayed price and the cancellation policy to confirm.")
    public ResponseEntity<PlatformBookingReviewResponse> reviewHotel(@Valid @RequestBody HotelReviewRequest request) {
        return ResponseEntity.ok(service.reviewHotel(request));
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

    @GetMapping("/summary")
    @Operation(summary = "Basic reports: bookings, sales and earnings in a date range, upcoming trips, bookings needing attention",
            description = "Dates are IST days, default this month to today; at most 366 days. Agents see only their own bookings.")
    public ResponseEntity<PlatformBookingSummaryResponse> summary(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(queryService.summary(from, to));
    }

    @PostMapping("/{id}/tickets")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER','AGENT')")
    @Operation(summary = "Record a customer e-ticket download", description = "Ticketed flights only; the markup is folded into the printed price and never shown on the ticket.")
    public ResponseEntity<TicketDownloadResponse> createTicket(@PathVariable String id, @Valid @RequestBody TicketCreateRequest request) {
        return ResponseEntity.ok(ticketService.create(id, request.getMarkup()));
    }

    @GetMapping("/{id}/tickets")
    @Operation(summary = "E-ticket downloads of a booking, newest first")
    public ResponseEntity<List<TicketDownloadResponse>> tickets(@PathVariable String id) {
        return ResponseEntity.ok(ticketService.list(id));
    }

    @GetMapping("/{id}/tickets/{ticketId}/pdf")
    @Operation(summary = "The customer e-ticket PDF of one download", description = "Rebuilt on every open with the price frozen at download.")
    public ResponseEntity<byte[]> ticketPdf(@PathVariable String id, @PathVariable String ticketId) {
        PlatformBookingTicketService.TicketPdf pdf = ticketService.pdf(id, ticketId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.filename()).build().toString())
                .body(pdf.bytes());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One booking with its plain-language history")
    public ResponseEntity<PlatformBookingResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(queryService.get(id));
    }
}
