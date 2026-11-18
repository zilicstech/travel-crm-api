package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** A cancellation request as the platform admin works it: the agency's view plus the supplier figures and the prefills. */
@Schema(description = "A cancellation request in the platform queue")
public record AdminCancelRequestResponse(
        @Schema(description = "What the agency sees") CancelRequestResponse request,
        @Schema(description = "Platform booking id") String bookingId,
        @Schema(description = "FLIGHT or HOTEL", example = "FLIGHT") String product,
        @Schema(description = "Agency id") String tenantId,
        @Schema(description = "Agency name", example = "Sky Travels") String agencyName,
        @Schema(description = "PNR or hotel confirmation", example = "ABC123") String pnr,
        @Schema(description = "First departure or check-in (yyyy-MM-dd)", example = "2026-12-01") String travelDate,
        @Schema(description = "True when travel is within 24 hours or already past") boolean urgent,
        @Schema(description = "Refund the supplier gave (completed only)", example = "4200.00") BigDecimal supplierRefund,
        @Schema(description = "Voyra's fee taken (completed only)", example = "100.00") BigDecimal voyraFee,
        @Schema(description = "Supplier's cancellation reference") String supplierReference,
        @Schema(description = "Refund was allowed above the chosen passengers' share") boolean exceedsShare,
        @Schema(description = "When the admin picked it up") Instant startedAt,
        @Schema(description = "Prefills and caps for completing it; null once finished") Prefill prefill,
        @Schema(description = "The full booking; detail view only") PlatformAdminBookingResponse booking) {

    public @Schema(description = "Figures the complete form starts from") record Prefill(@Schema(description = "Chosen passengers' share of the fare, 0-1", example = "0.5") BigDecimal share,
                          @Schema(description = "Refund above this needs exceedsShare", example = "2250.00") BigDecimal softCap,
                          @Schema(description = "Refund can never be above this: the fare less refunds already posted", example = "4500.00") BigDecimal hardCap,
                          @Schema(description = "Commission still to take back at most", example = "95.00") BigDecimal remainingRecall,
                          @Schema(description = "Suggested commission to take back", example = "47.50") BigDecimal recall,
                          @Schema(description = "Voyra's fee setting", example = "0.00") BigDecimal voyraFee) {
    }
}
