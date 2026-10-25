package com.voyra.crm.models.booking;

import java.math.BigDecimal;

public record CancelResult(boolean accepted, BigDecimal refund, String message) {
}
