package com.voyra.crm.util;

import com.voyra.crm.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingSortTest {

    @Test
    void blankSortsNewestBookingFirst() {
        assertThat(BookingSort.parse(null)).isEqualTo(Sort.by(Sort.Direction.DESC, "bookingNumber"));
    }

    @Test
    void bookingDateSortsWithTheNumberAsTieBreak() {
        assertThat(BookingSort.parse("bookingDate,asc")).isEqualTo(
                Sort.by(Sort.Direction.ASC, "bookingDate").and(Sort.by(Sort.Direction.DESC, "bookingNumber")));
    }

    @Test
    void travelDateSortsBookingsWithNoDateLast() {
        assertThat(BookingSort.parse("journeyDate,desc")).isEqualTo(Sort.by(Sort.Direction.ASC, "noJourneyDate")
                .and(Sort.by(Sort.Direction.DESC, "departureDate")).and(Sort.by(Sort.Direction.DESC, "bookingNumber")));
    }

    @Test
    void unknownColumnIsRejected() {
        assertThatThrownBy(() -> BookingSort.parse("profit,desc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profit");
    }

    @Test
    void bookingRefIsZeroPaddedToEightDigitsAndGrowsPastThem() {
        assertThat(BookingService.bookingRef(42L)).isEqualTo("BK00000042");
        assertThat(BookingService.bookingRef(123456789L)).isEqualTo("BK123456789");
        assertThat(BookingService.bookingRef(null)).isNull();
    }
}
