package com.voyra.crm.service.booking;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.models.booking.*;

import java.util.List;

/**
 * Supplier abstraction for booking flights, selected by {@code app.flight-booking.provider}
 * (mock default | tripjack). {@link #book} must be sent at most once per review id; a transport
 * timeout surfaces as {@code SupplierTimeoutException} and the caller then polls {@link #bookingDetails}.
 */
public interface FlightBookingProvider extends BookingSupplier {

    /**
     * Prices the offers as ONE booking: one id for one-way or a COMBO round trip, onward then return for a split round trip.
     * @throws com.voyra.crm.exception.FareUnavailableException when the fare is gone (or the pair can't be booked together)
     */
    FareReview review(List<String> offerIds, int adults, int children, int infants);

    default FareReview review(String offerId, int adults, int children, int infants) {
        return review(List.of(offerId), adults, children, infants);
    }

    /** Review from the agency request; only the mock reads its display hints, real suppliers use their own itinerary. */
    default FareReview review(FlightReviewRequest r) {
        return review(offerIds(r), r.getAdults(), r.getChildren(), r.getInfants());
    }

    static List<String> offerIds(FlightReviewRequest r) {
        return r.getReturnOfferId() == null || r.getReturnOfferId().isBlank()
                ? List.of(r.getOfferId()) : List.of(r.getOfferId(), r.getReturnOfferId());
    }
}
