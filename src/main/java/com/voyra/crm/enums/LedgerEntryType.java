package com.voyra.crm.enums;

/**
 * What kind of event a {@code customer_ledger_entry} row records. {@code REVERSAL} undoes an
 * {@code INVOICE_RAISED} debit when a tax invoice is cancelled before any receipt exists against
 * it - the normal correction path once money has moved is a credit note (E6), not a reversal.
 */
public enum LedgerEntryType {
    INVOICE_RAISED, PAYMENT_RECEIVED, CREDIT_NOTE_ISSUED, REFUND_PAID, OPENING_BALANCE, REVERSAL
}
