package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplier abstraction so the search endpoint never knows which vendor answered it: one implementation per
 * {@link SupplierProvider}, called once for every supplier row that has flight search switched on.
 */
public interface FlightSearchProvider {

    SupplierProvider provider();

    /** Searches with the given supplier row (the mock ignores it). */
    List<FlightOfferResponse> search(FlightSearchQuery query, ResolvedCredential supplier);

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
