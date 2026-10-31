package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookingCompletionEndDateTest {

    @Test
    void aTransferEndsOnItsDayAndATripWithNoDatesHasNoEnd() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        assertThat(BookingCompletionService.endDate(Booking.builder().transferDate(day).journeyDate(day.minusDays(3)).build())).isEqualTo(day);
        assertThat(BookingCompletionService.endDate(Booking.builder().build())).isNull();
    }

    @Test
    void theLatestVoucherDateWinsAndUndatedVouchersChangeNothing() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        BookingDocument undated = BookingDocument.builder().details(null).build();
        BookingDocument earlier = BookingDocument.builder().details(Map.of("returnDate", "2026-11-20")).build();
        BookingDocument later = BookingDocument.builder().details(Map.of("hotelCheckOut", "2026-12-05")).build();

        assertThat(BookingCompletionService.endDate(Booking.builder().journeyDate(day).build(), List.of(undated, earlier, later)))
                .isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(BookingCompletionService.endDate(Booking.builder().build(), List.of(earlier)))
                .isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(BookingCompletionService.endDate(Booking.builder().build(), List.of(undated))).isNull();
    }
}
