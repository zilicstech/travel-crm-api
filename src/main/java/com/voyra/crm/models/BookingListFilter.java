package com.voyra.crm.models;

import com.voyra.crm.enums.PaymentStatus;

import java.time.LocalDate;

/**
 * The Bookings screen's column filters, beyond type / status / agent / vendor / search. Every field
 * is optional; null means "any".
 *
 * @param assignedBy   the user who created the booking's lead (or the booking itself, when it has no lead)
 * @param remark       HAS or NONE - bookings with or without an accountant remark
 * @param purchaseCurrency a voucher bought in this currency; the base currency means none bought in another
 */
public record BookingListFilter(LocalDate bookedFrom, LocalDate bookedTo, LocalDate travelFrom, LocalDate travelTo,
                                String clientId, String cabin, String purchaseCurrency, PaymentStatus paymentStatus,
                                String assignedBy, String remark) {

    public static final BookingListFilter NONE = new BookingListFilter(null, null, null, null, null, null, null, null, null, null);
}
