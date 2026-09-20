package com.voyra.crm.enums;

/**
 * DRAFT is editable. APPROVED books the payable to the vendor ledger (see
 * {@code util.SupplierInvoiceLifecyclePolicy}). PARTIALLY_PAID/PAID are settlement-derived from
 * {@code balance_due}, mirroring {@code InvoiceLifecycle}.
 */
public enum SupplierInvoiceStatus {
    DRAFT, APPROVED, PARTIALLY_PAID, PAID, CANCELLED
}
