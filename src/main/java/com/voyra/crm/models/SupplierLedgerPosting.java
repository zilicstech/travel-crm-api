package com.voyra.crm.models;

import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to {@link com.voyra.crm.service.SupplierLedgerService#post}. Internal model - never
 * serialized, never persisted directly (it becomes one {@code entity.SupplierLedgerEntry} row).
 * Exactly one of {@code debitAmount}/{@code creditAmount} should be non-zero; the other is zero.
 */
public record SupplierLedgerPosting(
        String vendorId,
        LocalDate entryDate,
        SupplierLedgerEntryType entryType,
        SupplierLedgerSourceType sourceType,
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
