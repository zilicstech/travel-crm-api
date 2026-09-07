package com.voyra.crm.service;

import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;

import java.util.List;

/**
 * Supplier abstraction so the search endpoint never knows which vendor answered it - the
 * same shape as {@link FlightSearchProvider}. The mock implementation ships now for
 * dev/testing; a real vendor (Tripjack or otherwise) is a new implementation behind
 * {@code app.hotel-supplier.provider}, no caller changes.
 */
public interface HotelSearchProvider {

    List<HotelOfferResponse> search(HotelSearchQuery query);
}
