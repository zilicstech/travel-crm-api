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

    @Schema(description = "Fares charged to agencies on live (PROD) bookings ticketed in the window, as on the P&L", example = "125000.00")
    private BigDecimal gmv30d;

    @Schema(description = "Voyra net on those bookings, as on the P&L", example = "4210.50")
    private BigDecimal voyraNet30d;

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
}
