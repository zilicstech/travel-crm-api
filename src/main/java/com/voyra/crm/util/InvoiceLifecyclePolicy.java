package com.voyra.crm.util;

import com.voyra.crm.enums.InvoiceLifecycle;

/**
 * Centralises the transitions InvoiceDocumentService is allowed to make, so no service
 * hand-rolls its own status check. Only DRAFT -&gt; ISSUED -&gt; CANCELLED is reachable in this
 * epic; PROFORMA_ISSUED, PARTIALLY_PAID and PAID become reachable once Epic 4 (receipts and
 * the proforma detour) ships, and this file gains their transitions then rather than before.
 */
public final class InvoiceLifecyclePolicy {

    private InvoiceLifecyclePolicy() {
    }

    public static void assertEditable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.DRAFT) {
            throw new IllegalStateException(
                    "Invoice is " + status + " and can no longer be edited - correct it with a credit note instead");
        }
    }

    public static void assertDeletable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.DRAFT) {
            throw new IllegalStateException("Only a draft invoice can be deleted");
        }
    }

    public static void assertIssuable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.DRAFT) {
            throw new IllegalStateException("Only a draft invoice can be issued");
        }
    }

    public static void assertCancellable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.ISSUED) {
            throw new IllegalStateException("Only an issued invoice can be cancelled at this stage");
        }
    }
}
