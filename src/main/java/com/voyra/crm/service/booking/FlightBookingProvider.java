package com.voyra.crm.service.booking;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.*;

import java.util.List;

/**
 * Supplier abstraction for booking flights, one bean per {@link com.voyra.crm.enums.SupplierProvider}. {@link #book}
 * must be sent at most once per review id; a transport timeout surfaces as {@code SupplierTimeoutException} and the
 * caller then polls {@link #bookingDetails}. Offer ids are the supplier's own, without the search namespace.
 */
public interface FlightBookingProvider extends BookingSupplier {

    /**
     * Prices the offers as ONE booking: one id for one-way or a COMBO round trip, onward then return for a split round trip.
     * @throws com.voyra.crm.exception.FareUnavailableException when the fare is gone (or the pair can't be booked together)
     */
    FareReview review(List<String> offerIds, int adults, int children, int infants, ResolvedCredential supplier);

    /** Review from the agency request; only the mock reads its display hints, real suppliers use their own itinerary. */
    default FareReview review(FlightReviewRequest r, ResolvedCredential supplier) {
        return review(offerIds(r), r.getAdults(), r.getChildren(), r.getInfants(), supplier);
    }

    static List<String> offerIds(FlightReviewRequest r) {
        return r.getReturnOfferId() == null || r.getReturnOfferId().isBlank()
                ? List.of(r.getOfferId()) : List.of(r.getOfferId(), r.getReturnOfferId());
    }
}
