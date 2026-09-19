package com.voyra.crm.enums;

/**
 * Whether {@code booking.paymentStatus} is a human's manual PATCH or derived from real
 * invoices/receipts. Flips MANUAL -&gt; DERIVED the first time a tax invoice is issued against
 * the booking, and never flips back - see {@code service.BookingAccountingSync}.
 */
public enum PaymentStatusSource {
    MANUAL, DERIVED
}
