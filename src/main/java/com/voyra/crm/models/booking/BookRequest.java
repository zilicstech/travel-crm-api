package com.voyra.crm.models.booking;

import java.math.BigDecimal;
import java.util.List;

/** {@code totalFare} must be the exact reviewed total - it is sent as the payment amount. */
public record BookRequest(String reviewId, BigDecimal totalFare, List<PaxDetail> passengers, String email, String phone) {
}
