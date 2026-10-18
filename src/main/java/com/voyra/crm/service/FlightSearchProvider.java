package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplier abstraction so the search endpoint never knows which vendor answered it. The
 * mock implementation ships now for dev/testing and to exercise the flight-search modal
 * end to end; a real vendor (Tripjack or otherwise) is a new implementation behind
 * {@code app.flight-supplier.provider}, no caller changes - same shape as FileStorageService.
 */
public interface FlightSearchProvider {

    List<FlightOfferResponse> search(FlightSearchQuery query);

    /**
     * The fare for the whole searched party. A passenger type the supplier did not price is
     * charged the adult fare - over-quoting a child is recoverable, under-quoting a family is not.
     */
    static BigDecimal partyTotal(FlightSearchQuery query, BigDecimal adultFare, BigDecimal childFare, BigDecimal infantFare) {
        BigDecimal total = adultFare.multiply(BigDecimal.valueOf(Math.max(1, query.getAdults())));
        total = total.add((childFare != null ? childFare : adultFare).multiply(BigDecimal.valueOf(query.getChildren())));
        total = total.add((infantFare != null ? infantFare : adultFare).multiply(BigDecimal.valueOf(query.getInfants())));
        return total;
    }

    static int passengers(FlightSearchQuery query) {
        return Math.max(1, query.getAdults()) + query.getChildren() + query.getInfants();
    }
}
