package com.voyra.crm.enums;

/**
 * How an invoice's GST-taxable base is derived - FRD US-ACC-2.1. Orthogonal to
 * {@link TaxTreatment}: this decides <em>what</em> is taxable, {@link TaxTreatment} decides
 * <em>how</em> that base is taxed. Null on an {@code Invoice} is treated as {@link #PRINCIPAL}
 * everywhere - the pre-existing behaviour.
 */
public enum InvoiceBillingModel {

    /** Tax the full package price. The default, and today's only behaviour. */
    PRINCIPAL,

    /**
     * Pass the booking's supplier net costs through as a non-taxable disbursement; tax only the
     * agency's service fee / markup. Reproduces the client's own
     * "(Reimbursement of air ticket issued by airlines)" invoice.
     */
    COMMISSION_AGENT
}
