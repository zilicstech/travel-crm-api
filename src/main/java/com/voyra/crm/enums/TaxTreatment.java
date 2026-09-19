package com.voyra.crm.enums;

/**
 * How GST applies to one invoice, determined once at draft time by {@code TaxEngine} and then
 * persisted - it never re-runs after issue (see the reproducibility rule in the accounting
 * architecture notes).
 */
public enum TaxTreatment {
    INTRA_STATE, INTER_STATE, EXPORT_OF_SERVICE, EXEMPT
}
