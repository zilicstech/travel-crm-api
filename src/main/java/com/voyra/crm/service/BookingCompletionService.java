package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Completes a tenant's confirmed bookings once the trip is over - nobody marks a booking
 * Completed by hand. Its own {@code REQUIRES_NEW} bean so the nightly job's per-tenant work runs
 * in the tenant's schema (blueprint §3.5), same as {@link RevenueRecognitionTenantService}.
 */
@Service
@RequiredArgsConstructor
public class BookingCompletionService {

    private final BookingRepository bookingRepository;

    /** @return how many bookings were completed */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int completeFinished(LocalDate today) {
        int completed = 0;
        for (Booking b : bookingRepository.findByBookingStatus(BookingStatus.CONFIRMED)) {
            LocalDate end = endDate(b);
            if (end != null && end.isBefore(today)) {
                b.setBookingStatus(BookingStatus.COMPLETED);
                b.setUpdatedAt(LocalDateTime.now());
                bookingRepository.save(b);
                completed++;
            }
        }
        return completed;
    }

    /** The trip's last day: the return, else check-out, else the transfer, else the outbound date. */
    static LocalDate endDate(Booking b) {
        if (b.getReturnDate() != null) return b.getReturnDate();
        if (b.getHotelCheckOut() != null) return b.getHotelCheckOut();
        if (b.getTransferDate() != null) return b.getTransferDate();
        return b.getJourneyDate() != null ? b.getJourneyDate() : b.getDepartureDate();
    }
}
