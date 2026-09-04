package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;

import java.util.List;

/**
 * Supplier abstraction so the search endpoint never knows which vendor answered it. The
 * mock implementation ships now for dev/testing and to exercise the flight-search modal
 * end to end; a real vendor (Tripjack or otherwise) is a new implementation behind
 * {@code app.flight-supplier.provider}, no caller changes - same shape as FileStorageService.
 */
public interface FlightSearchProvider {

    List<FlightOfferResponse> search(FlightSearchQuery query);
}
