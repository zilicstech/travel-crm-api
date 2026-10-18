package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * The one thing a booking needs next, for the Bookings list - the first open step of the
 * booking-management lifecycle (see {@code BookingLifecycleService}), in the same order. Null
 * when nothing is left to do.
 */
public final class BookingNextStep {

    private BookingNextStep() {
    }

    public static String of(Booking b, int documentCount) {
        if (b.getBookingStatus() == BookingStatus.CANCELLED) {
            BigDecimal refund = nz(b.getCustomerRefundDue()).subtract(nz(b.getRefundedTotalInr()));
            if (refund.signum() > 0) return "Refund client " + rupees(refund);
            BigDecimal fromSupplier = nz(b.getSupplierRefundExpected()).subtract(nz(b.getSupplierRefundReceived()));
            if (b.getSupplierRefundClaimAmount() != null) return "Check supplier refund in bank " + rupees(b.getSupplierRefundClaimAmount());
            if (fromSupplier.signum() > 0) return "Record supplier refund " + rupees(fromSupplier);
            boolean anyRefund = nz(b.getCustomerRefundDue()).signum() > 0 || nz(b.getSupplierRefundExpected()).signum() > 0;
            return anyRefund && b.getRefundCheckedAt() == null ? "Cross-check refunds" : null;
        }
        if (b.getBookingStatus() == BookingStatus.PENDING) return "Confirm with supplier";
        if (documentCount == 0) return "Upload voucher";
        BigDecimal invoiced = nz(b.getInvoicedTotalInr());
        if (invoiced.signum() == 0 && nz(b.getSellingPrice()).signum() > 0) return "Generate invoice";
        BigDecimal toCollect = invoiced.subtract(nz(b.getReceivedTotalInr()));
        if (toCollect.signum() > 0) return "Collect " + rupees(toCollect);
        return null;
    }

    public static String rupees(BigDecimal v) {
        return "₹" + String.format(Locale.ENGLISH, "%,.0f", nz(v));
    }

    private static BigDecimal nz(BigDecimal v) {
        return Objects.requireNonNullElse(v, BigDecimal.ZERO);
    }
}
