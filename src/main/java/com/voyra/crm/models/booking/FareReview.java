package com.voyra.crm.models.booking;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency,
                         String fareRules, Instant expiresAt, JsonNode itinerary) {
}
