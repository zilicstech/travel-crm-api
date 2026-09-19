package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A client's statement of account for a date range, with a running balance computed over an ordered fetch (not stored) so it can never drift from the underlying rows.")
public class LedgerStatementResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(example = "2026-04-01")
    private LocalDate from;

    @Schema(example = "2026-09-19")
    private LocalDate to;

    @Schema(description = "Running balance immediately before the first row in range", example = "0.00")
    private BigDecimal openingBalanceInr;

    @Schema(description = "Running balance after the last row - equals SUM(invoice.balanceDueInr) for this client's non-cancelled invoices, plus any opening balance", example = "25120.00")
    private BigDecimal closingBalanceInr;

    @Schema(description = "Every entry in range, oldest first")
    private List<LedgerEntryResponse> entries;
}
