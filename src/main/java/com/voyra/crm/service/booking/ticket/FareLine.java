package com.voyra.crm.service.booking.ticket;

import java.math.BigDecimal;

/** One printed fare line on the customer ticket. */
public record FareLine(String label, BigDecimal amount) {
}
