package com.voyra.crm.enums;

/**
 * DRAFT is freely editable and consumes no number. ISSUED locks the number, freezes every
 * tax-reversal figure, and posts the ledger credit - from there only {@code cancel} (when nothing
 * has been refunded yet) or a refund payout can follow. CANCELLED is terminal.
 */
public enum CreditNoteStatus {
    DRAFT, ISSUED, CANCELLED
}
