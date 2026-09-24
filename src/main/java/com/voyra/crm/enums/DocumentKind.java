package com.voyra.crm.enums;

/**
 * A numbering series in {@code document_number_sequence}. Each has its own prefix and never
 * shares a counter with another.
 *
 * <p>{@code TAX_INVOICE} and {@code PROFORMA} are the pre-redesign generic invoice series, kept
 * only so history compiled against them still reads correctly - no new document allocates
 * against either. Every new customer invoice numbers off the {@link InvoiceServiceCategory} it
 * carries instead, one series per category, via {@link #forInvoiceCategory}.
 */
public enum DocumentKind {
    TAX_INVOICE, PROFORMA, RECEIPT, CREDIT_NOTE, PAYMENT_VOUCHER,
    AIR_INTERNATIONAL_INVOICE, AIR_DOMESTIC_INVOICE, HOTEL_INVOICE, RAIL_INVOICE,
    TRANSPORT_INVOICE, VISA_INVOICE, PACKAGE_INVOICE, MISCELLANEOUS_INVOICE;

    public static DocumentKind forInvoiceCategory(InvoiceServiceCategory category) {
        return switch (category) {
            case AIR_INTERNATIONAL -> AIR_INTERNATIONAL_INVOICE;
            case AIR_DOMESTIC -> AIR_DOMESTIC_INVOICE;
            case HOTEL -> HOTEL_INVOICE;
            case RAIL -> RAIL_INVOICE;
            case TRANSPORT -> TRANSPORT_INVOICE;
            case VISA -> VISA_INVOICE;
            case PACKAGE -> PACKAGE_INVOICE;
            case MISCELLANEOUS -> MISCELLANEOUS_INVOICE;
        };
    }
}
