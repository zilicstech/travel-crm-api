package com.voyra.crm.service.booking;

import com.voyra.crm.models.booking.*;

import java.time.LocalDate;

/**
 * Supplier abstraction for booking hotel rooms, selected by {@code app.hotel-booking.provider}
 * (mock default | tripjack). Same contract as {@link FlightBookingProvider}: {@link #book} is sent at most once
 * per review id and a transport timeout surfaces as {@code SupplierTimeoutException}; {@code TICKETED} means confirmed.
 */
public interface HotelBookingProvider {

    /**
     * Re-prices one room option for the stay; {@link FareReview#baseFare()} is the room rate excluding taxes (null when the
     * supplier does not say), {@code cancellationPolicy} and {@code freeCancellationUntil} describe the rate's rules.
     *
     * @throws com.voyra.crm.exception.FareUnavailableException when the room is gone
     */
    FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut, int rooms);

    /** @throws com.voyra.crm.exception.SupplierTimeoutException on a transport timeout (outcome unknown) */
    BookResult book(BookRequest request);

    BookResult bookingDetails(String supplierBookingId);

    CancelQuote cancelQuote(String supplierBookingId);

    CancelResult cancel(String supplierBookingId);
}
