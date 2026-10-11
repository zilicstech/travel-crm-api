package com.voyra.crm.enums;

/**
 * DRAFT is editable. PENDING_APPROVAL is a draft whose total exceeds the booking's quoted cost
 * cap for that vendor (see {@code ACCOUNTING_EXPANSION_ARCHITECTURE.md} Decision 7, Rule 7.5) -
 * only an Agency Owner supplying an override reason can move it to APPROVED. APPROVED books the
 * payable to the vendor ledger (see {@code util.SupplierInvoiceLifecyclePolicy}). PARTIALLY_PAID
 * /PAID are settlement-derived from {@code balance_due}, mirroring {@code InvoiceLifecycle}.
 */
public enum SupplierInvoiceStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, PARTIALLY_PAID, PAID, CANCELLED
}
