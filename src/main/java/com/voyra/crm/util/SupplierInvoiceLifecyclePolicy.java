package com.voyra.crm.util;

import com.voyra.crm.enums.SupplierInvoiceStatus;

import java.math.BigDecimal;

/**
 * Centralises the transitions {@code SupplierInvoiceService} and {@code SupplierPaymentService}
 * are allowed to make - the direct sibling of {@code InvoiceLifecyclePolicy} on the AR side.
 *
 * <pre>
 * DRAFT --approve--&gt; APPROVED (posts BILL_BOOKED to the vendor ledger)
 * APPROVED / PARTIALLY_PAID --payment--&gt; {@link #deriveFromBalance} decides PARTIALLY_PAID vs PAID
 * {DRAFT, APPROVED} --cancel--&gt; CANCELLED (blocked once any payment exists)
 * </pre>
 */
public final class SupplierInvoiceLifecyclePolicy {

    private SupplierInvoiceLifecyclePolicy() {
    }

    public static void assertEditable(SupplierInvoiceStatus status) {
        if (status != SupplierInvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Bill is " + status + " and can no longer be edited - correct it with a supplier credit note instead");
        }
    }

    public static void assertDeletable(SupplierInvoiceStatus status) {
        if (status != SupplierInvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only a draft bill can be deleted");
        }
    }

    public static void assertApprovable(SupplierInvoiceStatus status) {
        if (status != SupplierInvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only a draft bill can be approved");
        }
    }

    public static void assertCancellable(SupplierInvoiceStatus status) {
        if (status != SupplierInvoiceStatus.DRAFT && status != SupplierInvoiceStatus.APPROVED) {
            throw new IllegalStateException("Only a draft or approved bill can be cancelled at this stage");
        }
    }

    public static void assertPayable(SupplierInvoiceStatus status) {
        if (status != SupplierInvoiceStatus.APPROVED && status != SupplierInvoiceStatus.PARTIALLY_PAID) {
            throw new IllegalStateException("Payments can only be recorded against an approved or partially paid bill");
        }
    }

    public static SupplierInvoiceStatus deriveFromBalance(BigDecimal grandTotal, BigDecimal balanceDue) {
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            return SupplierInvoiceStatus.PAID;
        }
        if (balanceDue.compareTo(grandTotal) < 0) {
            return SupplierInvoiceStatus.PARTIALLY_PAID;
        }
        return SupplierInvoiceStatus.APPROVED;
    }
}
