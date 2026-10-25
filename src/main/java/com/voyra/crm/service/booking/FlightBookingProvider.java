package com.voyra.crm.service.booking;

import com.voyra.crm.models.booking.*;

/**
 * Supplier abstraction for booking flights, selected by {@code app.flight-booking.provider}
 * (mock default | tripjack). {@link #book} must be sent at most once per review id; a transport
 * timeout surfaces as {@code SupplierTimeoutException} and the caller then polls {@link #bookingDetails}.
 */
public interface FlightBookingProvider {

    /** @throws com.voyra.crm.exception.FareUnavailableException when the fare is gone */
    FareReview review(String offerId, int adults, int children, int infants);

    /** @throws com.voyra.crm.exception.SupplierTimeoutException on a transport timeout (outcome unknown) */
    BookResult book(BookRequest request);

    BookResult bookingDetails(String supplierBookingId);

    CancelQuote cancelQuote(String supplierBookingId);

    CancelResult cancel(String supplierBookingId);
}
