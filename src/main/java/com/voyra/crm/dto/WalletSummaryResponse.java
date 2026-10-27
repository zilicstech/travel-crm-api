package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@Schema(description = "An agency wallet at a glance")
public class WalletSummaryResponse {

    @Schema(description = "Money in the wallet", example = "12000.00")
    private BigDecimal balance;

    @Schema(description = "Part of the balance held for open bookings", example = "2000.00")
    private BigDecimal held;

    @Schema(description = "Balance minus held", example = "10000.00")
    private BigDecimal available;

    @Schema(description = "Currency", example = "INR")
    private String currency;

    @Schema(description = "OPEN or CLOSED", example = "OPEN")
    private String status;

    @Schema(description = "True for a test wallet", example = "false")
    private Boolean isTest;

    @Schema(description = "Low-balance alert threshold; null when off", example = "10000.00", nullable = true)
    private BigDecimal lowBalanceThreshold;

    @Schema(description = "Receivables the agency still owes Voyra")
    private List<ReceivableResponse> dueReceivables;

    @Schema(description = "Top-up requests awaiting a decision", example = "1")
    private int pendingTopups;
}
