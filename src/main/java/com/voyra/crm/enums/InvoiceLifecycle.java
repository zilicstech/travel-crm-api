package com.voyra.crm.enums;

/**
 * DRAFT is the only mutable state. PROFORMA_ISSUED, PARTIALLY_PAID and PAID are reachable once
 * Epic 4 (receipts and the proforma detour) ships; this epic only exercises
 * DRAFT -&gt; ISSUED -&gt; CANCELLED. See {@code util.InvoiceLifecyclePolicy} for the enforced
 * transitions.
 */
public enum InvoiceLifecycle {
    DRAFT, PROFORMA_ISSUED, ISSUED, PARTIALLY_PAID, PAID, CANCELLED
}
