package com.voyra.crm.models.booking;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code totalFare} must be the exact reviewed total - it is sent as the payment amount. {@code rooms} splits the
 * passengers per hotel room (first guest of each room is its lead) and {@code leadPan} is the lead guest's PAN when
 * given; flight providers ignore both.
 */
public record BookRequest(String reviewId, BigDecimal totalFare, List<PaxDetail> passengers, String email, String phone,
                          List<List<PaxDetail>> rooms, String leadPan) {

    public BookRequest(String reviewId, BigDecimal totalFare, List<PaxDetail> passengers, String email, String phone) {
        this(reviewId, totalFare, passengers, email, phone, List.of(passengers), null);
    }
}
