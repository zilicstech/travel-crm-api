package com.voyra.crm.controller;

import com.voyra.crm.dto.BookingCancelPreviewResponse;
import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingCostComponentRequest;
import com.voyra.crm.dto.RefundSettlementRequest;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.dto.BookingCostComponentResponse;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingDeadlineUpdateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.service.BookingLogService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.FeedbackLinkService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final BookingLogService bookingLogService;
    private final BookingCancellationService bookingCancellationService;
    private final FeedbackLinkService feedbackLinkService;

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a booking", description = "Refused once any accounting or feedback record references it - cancel it instead.")
    public ResponseEntity<Void> deleteBooking(@PathVariable String id) {
        bookingService.deleteBooking(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    @Operation(summary = "Create a booking", description = "Profit is always server-computed from sellingPrice - netCost.")
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody BookingCreateRequest request) {
        return ResponseEntity.ok(bookingService.createBooking(request));
    }

    @PostMapping(value = "/log", consumes = "multipart/form-data")
    @Operation(summary = "Log a confirmed booking with its voucher",
            description = "Parts: 'booking' (JSON BookingLogRequest) and 'file' (the voucher, required). "
                    + "Created CONFIRMED. With serviceId, the booking is filled from the lead's service.")
    public ResponseEntity<BookingResponse> logBooking(@Valid @RequestPart("booking") BookingLogRequest request,
                                                      @RequestPart(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok(bookingLogService.log(request, file));
    }

    @GetMapping("/unbilled")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "Unbilled bookings workbench",
            description = "CONFIRMED bookings with no live invoice yet - FRD US-ACC-1.1. Computed server-side, "
                    + "not derived by the caller from the full booking list.")
    public ResponseEntity<List<BookingResponse>> listUnbilledBookings() {
        return ResponseEntity.ok(bookingService.listUnbilledBookings());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "List bookings",
            description = "Agents see only their own bookings; Owners and Accountants see the whole agency "
                    + "(the accounts module bills against any booking). Supply ?page= for a paged envelope; "
                    + "omit it for the full list as a plain array. destination/agentId/vendorId are the "
                    + "FRD US-ACC-6.1 profitability filters - type doubles as package category.")
    public ResponseEntity<Object> listBookings(
            @RequestParam(value = "type", required = false) BookingType type,
            @RequestParam(value = "status", required = false) BookingStatus status,
            @RequestParam(value = "destination", required = false) String destination,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "vendorId", required = false) String vendorId,
            @Parameter(description = "Search booking no. (42, BK-00042), client, destination or PNR/reference - paged list only")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "bookingNumber | clientName | sellingPrice | bookingDate | journeyDate, then ,asc or ,desc. "
                    + "Paged list only; default bookingNumber,desc")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "true: only bookings with client money or a refund still open - paged list only")
            @RequestParam(value = "moneyPending", required = false, defaultValue = "false") boolean moneyPending,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? bookingService.listBookings(type, status, destination, agentId, vendorId)
                : bookingService.listBookings(type, status, destination, agentId, vendorId, q, sort, moneyPending, pageable));
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

    @GetMapping("/{id}/cancel-preview")
    @Operation(summary = "What cancelling would refund", description = "Customer refund due and supplier refund expected for the given charges. Writes nothing.")
    public ResponseEntity<BookingCancelPreviewResponse> cancelPreview(@PathVariable String id,
            @RequestParam(value = "supplierCharge", required = false) java.math.BigDecimal supplierCharge,
            @RequestParam(value = "agencyFee", required = false) java.math.BigDecimal agencyFee) {
        return ResponseEntity.ok(bookingCancellationService.preview(id, supplierCharge, agencyFee));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking", description = "Issues the credit note / supplier credit note automatically and records what each side owes.")
    public ResponseEntity<BookingResponse> cancel(@PathVariable String id, @Valid @RequestBody BookingCancelRequest request) {
        return ResponseEntity.ok(bookingCancellationService.cancel(id, request));
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Cancelled bookings with money still to move", description = "Customer refunds to pay and supplier refunds to receive.")
    public ResponseEntity<List<BookingResponse>> listRefunds() {
        return ResponseEntity.ok(bookingCancellationService.listOutstanding());
    }

    @PostMapping("/{id}/refunds/customer")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Record a refund paid to the customer")
    public ResponseEntity<BookingResponse> payCustomer(@PathVariable String id, @Valid @RequestBody RefundSettlementRequest request) {
        return ResponseEntity.ok(bookingCancellationService.payCustomer(id, request));
    }

    @PostMapping("/{id}/refunds/supplier")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
    @Operation(summary = "Record a refund received from the supplier",
            description = "Kept in the supplier wallet it is posted now; into the agency bank it waits for Accounts to confirm it.")
    public ResponseEntity<BookingResponse> recordSupplierRefund(@PathVariable String id, @Valid @RequestBody RefundSettlementRequest request) {
        return ResponseEntity.ok(bookingCancellationService.recordSupplierRefund(id, request));
    }

    @PostMapping("/{id}/refunds/supplier/confirm")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Confirm a supplier refund found in the bank", description = "Posts it to the books and closes the agent's claim.")
    public ResponseEntity<BookingResponse> confirmSupplierRefund(@PathVariable String id, @Valid @RequestBody RefundSettlementRequest request) {
        return ResponseEntity.ok(bookingCancellationService.confirmSupplierRefund(id, request));
    }

    @PostMapping("/{id}/refunds/supplier/reject")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "The claimed supplier refund is not in the bank", description = "Drops the claim; the agent records it again.")
    public ResponseEntity<BookingResponse> rejectSupplierRefund(@PathVariable String id) {
        return ResponseEntity.ok(bookingCancellationService.rejectSupplierRefund(id));
    }

    @PostMapping("/{id}/refunds/check")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Mark a settled cancellation as cross-checked by Accounts")
    public ResponseEntity<BookingResponse> markRefundChecked(@PathVariable String id) {
        return ResponseEntity.ok(bookingCancellationService.markChecked(id));
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

    @GetMapping("/{id}/cost-components")
    @Operation(summary = "List a booking's per-supplier cost components",
            description = "Empty for a booking placed before cost components existed - its net cost stays the single blended figure it always was.")
    public ResponseEntity<List<BookingCostComponentResponse>> listCostComponents(@PathVariable String id) {
        return ResponseEntity.ok(bookingService.listCostComponents(id));
    }

    @PutMapping("/{id}/cost-components")
    @Operation(summary = "Replace a booking's per-supplier cost components",
            description = "Replaces the whole set. Recomputes booking.netCost as the sum of the components when the list is non-empty.")
    public ResponseEntity<List<BookingCostComponentResponse>> replaceCostComponents(@PathVariable String id,
            @Valid @RequestBody List<BookingCostComponentRequest> request) {
        return ResponseEntity.ok(bookingService.replaceCostComponents(id, request));
    }
}
