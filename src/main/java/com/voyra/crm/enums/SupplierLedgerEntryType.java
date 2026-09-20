package com.voyra.crm.enums;

/**
 * What kind of event a {@code supplier_ledger_entry} row records. Signs are the mirror of
 * {@code LedgerEntryType}: BILL_BOOKED and ADVANCE_PAID both raise what the vendor holds from our
 * side (a bill raises the payable, an advance raises what we've prepaid) - see
 * {@code SupplierLedgerService} for the credit/debit convention.
 */
public enum SupplierLedgerEntryType {
    BILL_BOOKED, PAYMENT_MADE, ADVANCE_PAID, CREDIT_NOTE_RECEIVED, REFUND_RECEIVED, OPENING_BALANCE, REVERSAL
}
