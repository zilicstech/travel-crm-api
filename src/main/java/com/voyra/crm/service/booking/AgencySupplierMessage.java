package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PricingProduct;

import java.util.List;
import java.util.Locale;

/**
 * The few fixed sentences an agency sees about the supplier side of a booking. The supplier's own text (order status,
 * error codes, internal contacts) stays in {@code platform_booking.supplier_message} and the events, for platform admins.
 */
final class AgencySupplierMessage {

    static final String NOT_CONFIRMED_FLIGHT = "The airline could not confirm this fare";
    /** A split round trip the supplier refused: the pair may not combine (special-return fares) or one side is gone. */
    static final String NOT_CONFIRMED_PAIR = "The airline could not confirm this onward and return pair - pick another pair";
    static final String NOT_CONFIRMED_HOTEL = "The hotel could not confirm this rate";
    static final String FAILED = "Booking failed at the supplier";
    static final String CONFIRMING = "We're confirming with the supplier";
    static final String CANCEL_REFUSED = "The supplier refused the cancellation";
    /** Raw-text hints that the fare or room itself is gone, not a booking error. */
    private static final List<String> GONE = List.of("sold out", "no longer available", "not available", "unavailable",
            "fare changed", "price changed", "fare has changed", "price has changed", "expired");

    private AgencySupplierMessage() {
    }

    /** The agency's sentence for the row's state; null where there is nothing to say (reviewed, ticketed, cancelled...). */
    static String of(PlatformBooking b) {
        if (b.getStatus() == null) {
            return null;
        }
        return switch (b.getStatus()) {
            case BOOKING, UNKNOWN, CANCELLING -> CONFIRMING;
            case FAILED -> gone(b.getSupplierMessage()) ? (b.getProduct() == PricingProduct.HOTEL ? "Room no longer available"
                    : "Fare no longer available") : FAILED;
            default -> null;
        };
    }

    private static boolean gone(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return GONE.stream().anyMatch(text::contains);
    }
}
