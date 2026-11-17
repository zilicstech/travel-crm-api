package com.voyra.crm.models.booking;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code cancellationPolicy} and {@code freeCancellationUntil} are hotel-only (null for flights);
 * {@code documentIdRequired} is flight-only: the fare needs each traveller's ID number at booking;
 * {@code compliance} is hotel-only: what the room option asks for at booking ({@link HotelCompliance#NONE} for flights).
 */
public record FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                         String fareRules, Instant expiresAt, JsonNode itinerary,
                         String cancellationPolicy, Instant freeCancellationUntil, boolean documentIdRequired,
                         HotelCompliance compliance) {

    public FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                      String fareRules, Instant expiresAt, JsonNode itinerary,
                      String cancellationPolicy, Instant freeCancellationUntil, boolean documentIdRequired) {
        this(reviewId, totalFare, baseFare, currency, fareRules, expiresAt, itinerary, cancellationPolicy, freeCancellationUntil,
                documentIdRequired, HotelCompliance.NONE);
    }

    public FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                      String fareRules, Instant expiresAt, JsonNode itinerary,
                      String cancellationPolicy, Instant freeCancellationUntil) {
        this(reviewId, totalFare, baseFare, currency, fareRules, expiresAt, itinerary, cancellationPolicy, freeCancellationUntil, false);
    }

    public FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                      String fareRules, Instant expiresAt, JsonNode itinerary) {
        this(reviewId, totalFare, baseFare, currency, fareRules, expiresAt, itinerary, null, null, false);
    }
}
