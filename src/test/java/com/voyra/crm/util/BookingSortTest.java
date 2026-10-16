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
    void bookingDateSortsByCreatedDateWithTheNumberAsTieBreak() {
        assertThat(BookingSort.parse("bookingDate,asc")).isEqualTo(
                Sort.by(Sort.Direction.ASC, "createdDate").and(Sort.by(Sort.Direction.DESC, "bookingNumber")));
    }

    @Test
    void unknownColumnIsRejected() {
        assertThatThrownBy(() -> BookingSort.parse("netCost,desc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("netCost");
    }

    @Test
    void bookingRefIsZeroPaddedAndGrowsPastFiveDigits() {
        assertThat(BookingService.bookingRef(42L)).isEqualTo("BK-00042");
        assertThat(BookingService.bookingRef(123456L)).isEqualTo("BK-123456");
        assertThat(BookingService.bookingRef(null)).isNull();
    }
}
