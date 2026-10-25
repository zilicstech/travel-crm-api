package com.voyra.crm.models.booking;

import java.math.BigDecimal;

public record CancelQuote(BigDecimal refund, String message) {
}
