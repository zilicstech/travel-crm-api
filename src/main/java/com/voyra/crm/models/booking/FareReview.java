package com.voyra.crm.models.booking;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code cancellationPolicy} and {@code freeCancellationUntil} are hotel-only (null for flights). */
public record FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                         String fareRules, Instant expiresAt, JsonNode itinerary,
                         String cancellationPolicy, Instant freeCancellationUntil) {

    public FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                      String fareRules, Instant expiresAt, JsonNode itinerary) {
        this(reviewId, totalFare, baseFare, currency, fareRules, expiresAt, itinerary, null, null);
    }
}
