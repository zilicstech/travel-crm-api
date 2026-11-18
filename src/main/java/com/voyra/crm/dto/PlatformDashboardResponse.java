package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@Schema(description = "Platform home tiles: live money over the last 30 days and the queues waiting for an admin")
public class PlatformDashboardResponse {

    @Schema(description = "First IST day of the 30-day window", example = "2026-09-27")
    private LocalDate from;

    @Schema(description = "Last IST day (today)", example = "2026-10-26")
    private LocalDate to;

    @Schema(description = "Fares charged to agencies on live (PROD) bookings ticketed in the window", example = "125000.00")
    private BigDecimal gmv30d;

    @Schema(description = "Sum of agency wallet balances, test wallets excluded", example = "350000.00")
    private BigDecimal walletFloat;

    @Schema(description = "Applications submitted or under review", example = "3")
    private long pendingKyc;

    @Schema(description = "Bank-transfer top-ups waiting for approval", example = "2")
    private long pendingTopups;

    @Schema(description = "Bookings whose airline outcome is unknown", example = "1")
    private long unknownBookings;

    @Schema(description = "Cancellations the supplier has not answered", example = "0")
    private long cancellingBookings;

    @Schema(description = "Subscriptions past their due date", example = "2")
    private long pastDueSubscriptions;

    @Schema(description = "Emails that failed for good (notification outbox)", example = "0")
    private long failedEmails;

    @Schema(description = "Agencies' cancellation requests waiting for Voyra (open, in progress, completing)", example = "4")
    private long cancelRequests;

    @Schema(description = "The admin's work list: cancellation requests (soonest travel first), then supplier cancellations, unknown bookings, KYC and top-ups, oldest first; at most 20")
    private java.util.List<PendingTask> pendingTasks;

    @Schema(description = "How many tasks there are in all (the list shows at most 20)", example = "23")
    private long pendingTaskTotal;

    @Schema(description = "One item waiting for an admin")
    public record PendingTask(
            @Schema(description = "CANCEL_REQUEST, CANCELLING, UNKNOWN_BOOKING, KYC or TOPUP", example = "CANCEL_REQUEST") String kind,
            @Schema(description = "What it is", example = "Cancellation request - Sky Travels") String title,
            @Schema(description = "Detail line", example = "Passenger medically unfit · PNR ABC123") String subtitle,
            @Schema(description = "When it arrived") java.time.Instant createdAt,
            @Schema(description = "Travel date it is due by (yyyy-MM-dd), when known", example = "2026-12-01") String dueAt,
            @Schema(description = "Travel within 24 hours or already past") boolean urgent,
            @Schema(description = "Where to work it", example = "/platform/cancellations/abc") String href) {
    }
}
