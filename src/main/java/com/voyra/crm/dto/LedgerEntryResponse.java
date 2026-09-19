package com.voyra.crm.dto;

import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One row of a client's statement of account, with the running INR balance as of this row.")
public class LedgerEntryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "2026-09-19")
    private LocalDate entryDate;

    @Schema(example = "INVOICE_RAISED")
    private LedgerEntryType entryType;

    @Schema(example = "INVOICE")
    private LedgerSourceType sourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @Schema(example = "INV/2026-27/0001")
    private String documentNumber;

    @Schema(example = "Tax invoice INV/2026-27/0001 raised")
    private String narration;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "55120.00")
    private BigDecimal debitAmount;

    @Schema(example = "0.00")
    private BigDecimal creditAmount;

    @Schema(example = "55120.00")
    private BigDecimal debitAmountInr;

    @Schema(example = "0.00")
    private BigDecimal creditAmountInr;

    @Schema(description = "SUM(debit_inr - credit_inr) up to and including this row, for this client", example = "55120.00")
    private BigDecimal runningBalanceInr;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime createdAt;
}
