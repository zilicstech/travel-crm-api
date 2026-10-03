package com.voyra.crm.dto;

import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One movement on a single account's General Ledger, with the running balance after it.")
public class AccountLedgerLineResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String journalEntryId;

    @Schema(example = "JNV/2026-27/0001")
    private String entryNumber;

    @Schema(example = "2026-09-30")
    private LocalDate entryDate;

    @Schema(example = "INVOICE")
    private JournalSourceType sourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @Schema(example = "INVOICE_RAISED")
    private JournalPurpose purpose;

    @Schema(example = "POSTED")
    private JournalStatus status;

    @Schema(description = "The line's own narration, falling back to the entry's", example = "Invoice INV/2026-27/0012 raised")
    private String narration;

    @Schema(example = "USD")
    private String currencyCode;

    @Schema(description = "Foreign-currency amount, when the line was not in INR", example = "600.00")
    private BigDecimal foreignAmount;

    @Schema(example = "50000.00")
    private BigDecimal debitInr;

    @Schema(example = "0.00")
    private BigDecimal creditInr;

    @Schema(description = "Balance after this line, in the account's natural direction", example = "86500.00")
    private BigDecimal runningBalanceInr;
}
