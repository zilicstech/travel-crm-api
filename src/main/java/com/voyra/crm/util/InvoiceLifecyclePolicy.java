package com.voyra.crm.util;

import com.voyra.crm.enums.InvoiceLifecycle;

import java.math.BigDecimal;

/**
 * Centralises the transitions InvoiceDocumentService and PaymentReceiptService are allowed to
 * make, so no service hand-rolls its own status check.
 *
 * <pre>
 * DRAFT --issueProforma--&gt; PROFORMA_ISSUED --convertToTaxInvoice--&gt; (new row) ISSUED
 *   \--------issue---------------------------------------------------------------&gt; ISSUED
 * ISSUED / PARTIALLY_PAID --receipt--&gt; {@link #deriveFromBalance} decides PARTIALLY_PAID vs PAID
 * {DRAFT, PROFORMA_ISSUED, ISSUED} --cancel--&gt; CANCELLED
 * PAID / PARTIALLY_PAID --&gt; corrected via a credit note (Epic 6), never by cancelling
 * </pre>
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

    public static void assertProformaIssuable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.DRAFT) {
            throw new IllegalStateException("Only a draft invoice can be issued as a proforma");
        }
    }

    public static void assertConvertible(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.PROFORMA_ISSUED) {
            throw new IllegalStateException("Only an issued proforma can be converted to a tax invoice");
        }
    }

    public static void assertCancellable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.ISSUED && status != InvoiceLifecycle.PROFORMA_ISSUED) {
            throw new IllegalStateException("Only an issued invoice or proforma can be cancelled at this stage");
        }
    }

    /** A proforma's advance receipts are allowed too - see the class javadoc's diagram. */
    public static void assertReceivable(InvoiceLifecycle status) {
        if (status != InvoiceLifecycle.ISSUED
                && status != InvoiceLifecycle.PARTIALLY_PAID
                && status != InvoiceLifecycle.PROFORMA_ISSUED) {
            throw new IllegalStateException(
                    "Receipts can only be recorded against an issued invoice, a partially paid invoice, or an issued proforma");
        }
    }

    /** Never called for a proforma - its status is not settlement-derived, only a tax invoice's is. */
    public static InvoiceLifecycle deriveFromBalance(BigDecimal grandTotal, BigDecimal balanceDue) {
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            return InvoiceLifecycle.PAID;
        }
        if (balanceDue.compareTo(grandTotal) < 0) {
            return InvoiceLifecycle.PARTIALLY_PAID;
        }
        return InvoiceLifecycle.ISSUED;
    }
}
