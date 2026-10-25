package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "A reviewed fare, ready to confirm; never carries gross, Voyra's earnings or supplier ids")
public class PlatformBookingReviewResponse {

    @Schema(description = "Platform booking id to confirm", example = "b1d2e3f4-0000-0000-0000-000000000001")
    private String bookingId;

    @Schema(description = "What the wallet will be charged", example = "4500.00")
    private BigDecimal displayedFare;

    @Schema(description = "The search-time displayed fare, when it differs from the reviewed one", example = "4400.00")
    private BigDecimal previousDisplayedFare;

    @Schema(description = "True when the reviewed fare differs from the search fare; the agency must accept the new price", example = "false")
    private boolean priceChanged;

    @Schema(description = "Commission the agency earns after TDS, credited on ticketing", example = "120.00")
    private BigDecimal agencyEarns;

    @Schema(description = "Supplier fare rules shown before confirm", example = "Refundable, cancellation fee applies")
    private String fareRules;

    @Schema(description = "Hotels: the rate's cancellation policy, in plain words", example = "Free cancellation until 2026-11-29")
    private String cancellationPolicy;

    @Schema(description = "Hotels: free cancellation ends at this time, when the supplier says", example = "2026-11-29T00:00:00Z")
    private Instant freeCancellationUntil;

    @Schema(description = "Confirm before this time or review again", example = "2026-10-25T10:15:00Z")
    private Instant reviewExpiresAt;

    @Schema(description = "Wallet balance available to book right now", example = "10000.00")
    private BigDecimal walletAvailable;
}
