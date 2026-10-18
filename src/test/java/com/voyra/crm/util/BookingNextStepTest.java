package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookingNextStepTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Booking booking(BookingStatus status) {
        return Booking.builder().id("B1").bookingStatus(status).sellingPrice(d("10000")).netCost(d("8000"))
                .invoicedTotalInr(BigDecimal.ZERO).receivedTotalInr(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .customerRefundDue(BigDecimal.ZERO).supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO)
                .build();
    }

    @Test
    void walksTheLifecycleInOrder() {
        Booking b = booking(BookingStatus.PENDING);
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Confirm with supplier");

        b.setBookingStatus(BookingStatus.CONFIRMED);
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Upload voucher");
        assertThat(BookingNextStep.of(b, 1)).isEqualTo("Generate invoice");

        b.setInvoicedTotalInr(d("10000"));
        b.setReceivedTotalInr(d("4000"));
        assertThat(BookingNextStep.of(b, 1)).isEqualTo("Collect ₹6,000");

        b.setReceivedTotalInr(d("10000"));
        // Paying the supplier is Accounts' job on the bill, not a booking step.
        assertThat(BookingNextStep.of(b, 1)).isNull();
    }

    @Test
    void cancelledBookingsPointAtTheRefunds() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setCustomerRefundDue(d("7500"));
        b.setSupplierRefundExpected(d("6000"));
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Refund client ₹7,500");

        b.setRefundedTotalInr(d("7500"));
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Record supplier refund ₹6,000");

        b.setSupplierRefundClaimAmount(d("6000"));
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Check supplier refund in bank ₹6,000");
        b.setSupplierRefundClaimAmount(null);

        b.setSupplierRefundReceived(d("6000"));
        assertThat(BookingNextStep.of(b, 0)).isEqualTo("Cross-check refunds");

        b.setRefundCheckedAt(LocalDateTime.now());
        assertThat(BookingNextStep.of(b, 0)).isNull();
    }

    @Test
    void aCancelledBookingWithNothingToRefundNeedsNothing() {
        assertThat(BookingNextStep.of(booking(BookingStatus.CANCELLED), 0)).isNull();
    }
}
