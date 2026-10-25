package com.voyra.crm.service.booking;

import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.models.booking.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Supplier abstraction for booking hotel rooms, selected by {@code app.hotel-booking.provider}
 * (mock default | tripjack). Same contract as {@link FlightBookingProvider}: {@link #book} is sent at most once
 * per review id and a transport timeout surfaces as {@code SupplierTimeoutException}; {@code TICKETED} means confirmed.
 */
public interface HotelBookingProvider extends BookingSupplier {

    /**
     * Re-prices one room option for the stay and each room's occupancy (adults, child ages); {@link FareReview#baseFare()} is the room rate excluding taxes (null when the
     * supplier does not say), {@code cancellationPolicy} and {@code freeCancellationUntil} describe the rate's rules.
     *
     * @throws com.voyra.crm.exception.FareUnavailableException when the room is gone
     */
    FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut, List<HotelRoomRequest> rooms);
}
