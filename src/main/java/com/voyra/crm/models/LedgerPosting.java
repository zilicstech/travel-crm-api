package com.voyra.crm.models;

import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to {@link com.voyra.crm.service.CustomerLedgerService#post}. Internal model - never
 * serialized, never persisted directly (it becomes one {@code entity.CustomerLedgerEntry} row).
 * Exactly one of {@code debitInr}/{@code creditInr} should be non-zero; the other is zero.
 */
public record LedgerPosting(
        String clientId,
        LocalDate entryDate,
        LedgerEntryType entryType,
        LedgerSourceType sourceType,
        String sourceId,
        String documentNumber,
        String narration,
        String bookingId,
        String currencyCode,
        BigDecimal fxRateToInr,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        BigDecimal debitAmountInr,
        BigDecimal creditAmountInr
) {
}
