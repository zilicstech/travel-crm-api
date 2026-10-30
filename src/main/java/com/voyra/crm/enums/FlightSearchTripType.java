package com.voyra.crm.enums;

/**
 * How a flight search result is laid out. ROUND_TRIP_SPLIT: separate onward and return fares the agency pairs
 * (Tripjack's domestic shape); ROUND_TRIP_COMBO: one fare covers both ways (its international shape).
 * Distinct from {@link FlightTripType}, which is what a lead service asks for.
 */
public enum FlightSearchTripType {
    ONE_WAY, ROUND_TRIP_SPLIT, ROUND_TRIP_COMBO
}
