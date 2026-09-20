package com.voyra.crm.enums;

/** A numbering series in {@code document_number_sequence}. Each has its own prefix and never shares a counter with another. */
public enum DocumentKind {
    TAX_INVOICE, PROFORMA, RECEIPT, CREDIT_NOTE, PAYMENT_VOUCHER
}
