package com.voyra.crm.service.booking;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.models.booking.*;

/**
 * Supplier abstraction for booking flights, selected by {@code app.flight-booking.provider}
 * (mock default | tripjack). {@link #book} must be sent at most once per review id; a transport
 * timeout surfaces as {@code SupplierTimeoutException} and the caller then polls {@link #bookingDetails}.
 */
public interface FlightBookingProvider extends BookingSupplier {

    /** @throws com.voyra.crm.exception.FareUnavailableException when the fare is gone */
    FareReview review(String offerId, int adults, int children, int infants);

    /** Review from the agency request; only the mock reads its display hints, real suppliers use their own itinerary. */
    default FareReview review(FlightReviewRequest r) {
        return review(r.getOfferId(), r.getAdults(), r.getChildren(), r.getInfants());
    }
}
