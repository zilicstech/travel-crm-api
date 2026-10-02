package com.voyra.crm.enums;

/**
 * One constant per event in the posting-rule table, ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.5.
 * Combined with {@link JournalSourceType} and a {@code source_id}, this is what the idempotency
 * unique index keys on - a given (source, id, purpose) can post exactly once. Rows 9/9a/9b of the
 * posting-rule table are one purpose, {@link #SUPPLIER_PAYMENT_SETTLED}: the realized forex
 * gain/loss is a third line balancing the same settlement entry, not a separate journal.
 */
public enum JournalPurpose {

    /** Row 1 - PRINCIPAL invoice issued, booking departs in the future. */
    INVOICE_RAISED_DEFERRED,

    /** Row 2 - PRINCIPAL invoice issued, departure already past or the booking carries none. */
    INVOICE_RAISED_RECOGNIZED,

    /** Row 3 - COMMISSION_AGENT invoice issued. */
    INVOICE_RAISED_COMMISSION_AGENT,

    /** Row 4 - a receipt applied against an already-issued invoice. */
    RECEIPT_AGAINST_INVOICE,

    /** Row 5 - a receipt with no invoice yet (deposit / wallet top-up). */
    RECEIPT_ADVANCE,

    /** Row 6 - an existing client advance applied to an invoice. */
    ADVANCE_APPLIED,

    /** Row 7 - the departure-date revenue recognition job moving Unearned Tour Revenue to Sales. */
    REVENUE_RECOGNIZED,

    /** Row 8 - a supplier bill approved. */
    SUPPLIER_BILL_BOOKED,

    /** Rows 9/9a/9b - a supplier payment settled, with any realized forex variance as a third line. */
    SUPPLIER_PAYMENT_SETTLED,

    /** Row 10 - an advance/deposit paid to a supplier. */
    SUPPLIER_ADVANCE_PAID,

    /** Row 11 - an existing supplier advance applied to a bill. */
    SUPPLIER_ADVANCE_APPLIED,

    /** Row 12 - a client credit note issued. */
    CREDIT_NOTE_ISSUED,

    /** Row 13 - a refund paid out to a client. */
    CLIENT_REFUND_PAID,

    /** Row 14 - a supplier credit note received. */
    SUPPLIER_CREDIT_NOTE_RECEIVED,

    /** Row 15 - an online/gateway receipt, net of the processing fee. */
    GATEWAY_RECEIPT,

    /** Row 16 - a bank charge or interest line from a statement categorisation rule. */
    BANK_CHARGE_CATEGORIZED,

    /** Row 17 - an opening balance posted against a party control account or 3100. */
    OPENING_BALANCE_POSTED,

    /** Row 18 - the month-end unrealized mark-to-market revaluation of an open foreign payable. */
    FOREX_REVALUATION,

    /** Not in the posting-rule table - a human-entered journal, never posted automatically. */
    MANUAL_ENTRY
}
