package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Completes a tenant's confirmed bookings once the trip is over - nobody marks a booking
 * Completed by hand. Its own {@code REQUIRES_NEW} bean so the nightly job's per-tenant work runs
 * in the tenant's schema (blueprint §3.5), same as {@link RevenueRecognitionTenantService}.
 */
@Service
@RequiredArgsConstructor
public class BookingCompletionService {

    private final BookingRepository bookingRepository;
    private final com.voyra.crm.repository.BookingDocumentRepository bookingDocumentRepository;
    private final com.voyra.crm.repository.PlatformBookingRepository platformBookingRepository;

    private static final Set<PlatformBookingStatus> SETTLED = EnumSet.of(PlatformBookingStatus.TICKETED,
            PlatformBookingStatus.CANCELLED, PlatformBookingStatus.FAILED, PlatformBookingStatus.ABORTED);

    /** @return how many bookings were completed */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int completeFinished(LocalDate today) {
        int completed = 0;
        List<Booking> confirmed = bookingRepository.findByBookingStatus(BookingStatus.CONFIRMED);
        Set<String> live = livePlatformBookings(confirmed);
        for (Booking b : confirmed) {
            if (b.getPlatformBookingId() != null && live.contains(b.getPlatformBookingId())) {
                continue; // a mirror waits while its platform booking is still moving (e.g. a cancellation in flight)
            }
            LocalDate end = endDate(b, bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(b.getId()));
            if (end != null && end.isBefore(today)) {
                b.setBookingStatus(BookingStatus.COMPLETED);
                b.setUpdatedAt(LocalDateTime.now());
                bookingRepository.save(b);
                completed++;
            }
        }
        return completed;
    }

    /** Platform bookings of these mirrors not yet settled: anything but ticketed (live until travel) or terminal. */
    private Set<String> livePlatformBookings(List<Booking> bookings) {
        List<String> ids = bookings.stream().map(Booking::getPlatformBookingId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return platformBookingRepository.findAllById(ids).stream().filter(p -> !SETTLED.contains(p.getStatus()))
                .map(com.voyra.crm.entity.PlatformBooking::getId).collect(Collectors.toSet());
    }

    /** The trip's last day: the latest of the booking's own end and every voucher's dates (a return ticket may be its own voucher). */
    static LocalDate endDate(Booking b, java.util.List<com.voyra.crm.entity.BookingDocument> vouchers) {
        LocalDate end = endDate(b);
        for (com.voyra.crm.entity.BookingDocument v : vouchers) {
            LocalDate last = com.voyra.crm.util.VoucherDetails.lastDate(v.getDetails());
            if (last != null && (end == null || last.isAfter(end))) end = last;
        }
        return end;
    }

    /** The booking's own last day: the return, else check-out, else the transfer, else the outbound date. */
    static LocalDate endDate(Booking b) {
        if (b.getReturnDate() != null) return b.getReturnDate();
        if (b.getHotelCheckOut() != null) return b.getHotelCheckOut();
        if (b.getTransferDate() != null) return b.getTransferDate();
        return b.getJourneyDate() != null ? b.getJourneyDate() : b.getDepartureDate();
    }
}
