package com.voyra.crm.service.booking;

import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;

/**
 * The supplier calls the booking state machine makes after review, whatever the product. {@link #book} is sent at most
 * once per review id; a transport timeout surfaces as {@code SupplierTimeoutException} and the caller then polls
 * {@link #bookingDetails}. Picked per booking by {@link BookingProviderRegistry}.
 */
public interface BookingSupplier {

    /** @throws com.voyra.crm.exception.SupplierTimeoutException on a transport timeout (outcome unknown) */
    BookResult book(BookRequest request);

    BookResult bookingDetails(String supplierBookingId);

    CancelQuote cancelQuote(String supplierBookingId);

    CancelResult cancel(String supplierBookingId);
}
