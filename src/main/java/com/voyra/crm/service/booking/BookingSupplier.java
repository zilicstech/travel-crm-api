package com.voyra.crm.service.booking;

import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;

/**
 * The supplier calls the booking state machine makes after review, whatever the product. Every call takes the supplier
 * row the booking was reviewed on. {@link #book} is sent at most once per review id; a transport timeout surfaces as
 * {@code SupplierTimeoutException} and the caller then polls {@link #bookingDetails}. Picked per booking by
 * {@link SupplierRegistry#supplierFor}.
 */
public interface BookingSupplier {

    SupplierProvider provider();

    /** True when an accepted cancel is confirmed later (the poller settles it); false when it settles at once. */
    boolean cancelIsAsync();

    /** @throws com.voyra.crm.exception.SupplierTimeoutException on a transport timeout (outcome unknown) */
    BookResult book(BookRequest request, ResolvedCredential supplier);

    BookResult bookingDetails(String supplierBookingId, ResolvedCredential supplier);

    CancelQuote cancelQuote(String supplierBookingId, ResolvedCredential supplier);

    CancelResult cancel(String supplierBookingId, ResolvedCredential supplier);
}
