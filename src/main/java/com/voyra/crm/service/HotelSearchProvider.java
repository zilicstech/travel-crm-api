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

    /**
     * One hotel opened from a search: every photo, its description and policies, and every bookable room option
     * for the same dates and guests. {@code query.correlationId} ties it to that search.
     */
    com.voyra.crm.dto.HotelDetailResponse detail(String hotelId, HotelSearchQuery query);
}
