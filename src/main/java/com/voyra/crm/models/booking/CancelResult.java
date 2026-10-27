package com.voyra.crm.models.booking;

import java.math.BigDecimal;

/** {@code refund} null = unknown, route to manual review. */
public record CancelResult(boolean accepted, BigDecimal refund, String message) {
}
