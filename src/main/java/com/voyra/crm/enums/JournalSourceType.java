package com.voyra.crm.enums;

/** What document, if any, caused a journal entry to post. MANUAL is the only kind with no source_id and the only kind a human may post directly. */
public enum JournalSourceType {
    INVOICE, RECEIPT, CREDIT_NOTE, SUPPLIER_INVOICE, SUPPLIER_PAYMENT, SUPPLIER_CREDIT_NOTE,
    OPENING_BALANCE, REVALUATION, MANUAL
}
