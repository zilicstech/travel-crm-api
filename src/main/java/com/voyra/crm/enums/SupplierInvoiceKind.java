package com.voyra.crm.enums;

/**
 * PURCHASE is a normal supplier bill for a booking. ADM (Agency Debit Memo) is an airline/BSP
 * charging the agency after the fact for an error or policy violation - structurally an extra
 * payable, not a credit, so it is its own kind rather than a negative line item. MISC covers
 * anything else the agency owes a vendor outside a specific booking.
 */
public enum SupplierInvoiceKind {
    PURCHASE, ADM, MISC
}
