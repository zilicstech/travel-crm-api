package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingCompletionServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private com.voyra.crm.repository.BookingDocumentRepository bookingDocumentRepository;
    @InjectMocks private BookingCompletionService service;

    @Test
    void onlyTripsThatHaveEndedAreCompleted() {
        LocalDate today = LocalDate.of(2026, 10, 18);
        Booking returned = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED)
                .journeyDate(today.minusDays(5)).returnDate(today.minusDays(1)).build();
        Booking stillAway = Booking.builder().id("B2").bookingStatus(BookingStatus.CONFIRMED)
                .journeyDate(today.minusDays(5)).returnDate(today.plusDays(2)).build();
        Booking checkoutToday = Booking.builder().id("B3").bookingStatus(BookingStatus.CONFIRMED)
                .hotelCheckOut(today).build();
        Booking noDates = Booking.builder().id("B4").bookingStatus(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findByBookingStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(returned, stillAway, checkoutToday, noDates));

        assertThat(service.completeFinished(today)).isEqualTo(1);
        assertThat(returned.getBookingStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(stillAway.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(checkoutToday.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(noDates.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    /** A return flight on its own voucher keeps the booking open until it has flown. */
    @Test
    void aLaterVoucherDateKeepsTheBookingOpen() {
        Booking b = Booking.builder().id("B9").bookingStatus(BookingStatus.CONFIRMED).journeyDate(java.time.LocalDate.of(2026, 10, 1)).build();
        com.voyra.crm.entity.BookingDocument ret = com.voyra.crm.entity.BookingDocument.builder().id("D1").bookingId("B9")
                .details(java.util.Map.of("journeyDate", "2026-10-20")).build();
        assertThat(BookingCompletionService.endDate(b, java.util.List.of(ret))).isEqualTo(java.time.LocalDate.of(2026, 10, 20));
        assertThat(BookingCompletionService.endDate(b, java.util.List.of())).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
    }
}
