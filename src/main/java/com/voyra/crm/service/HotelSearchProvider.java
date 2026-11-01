package com.voyra.crm.service;

import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.HotelThumbnailResponse;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;

import java.util.List;

/**
 * Supplier abstraction so the search endpoint never knows which vendor answered it - the same shape as
 * {@link FlightSearchProvider}: one implementation per {@link SupplierProvider}, called once for every supplier row
 * that has hotel search switched on. Ids in and out are the supplier's own; the service namespaces them.
 */
public interface HotelSearchProvider {

    SupplierProvider provider();

    List<HotelOfferResponse> search(HotelSearchQuery query, ResolvedCredential supplier);

    /**
     * One hotel opened from a search: every photo, its description and policies, and every bookable room option
     * for the same dates and guests. {@code query.correlationId} ties it to that search.
     */
    HotelDetailResponse detail(String hotelId, HotelSearchQuery query, ResolvedCredential supplier);

    /**
     * Photo, stars and address for hotels already listed - the list is shown first, from the search alone, and these
     * fill in as they arrive. Hotels the supplier has nothing for are left out.
     */
    List<HotelThumbnailResponse> thumbnails(List<String> hotelIds, ResolvedCredential supplier);
}
