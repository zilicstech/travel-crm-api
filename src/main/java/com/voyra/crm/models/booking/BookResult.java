package com.voyra.crm.models.booking;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code amount} is the total the supplier charged, null when it did not say; a figure other than the reviewed total is a variance.
 * {@code lines} is the per passenger and sector detail behind the flat {@code pnr} and {@code ticketNumbers}; empty when unknown.
 */
public record BookResult(String supplierBookingId, SupplierBookingStatus status, String pnr,
                         List<String> ticketNumbers, String message, BigDecimal amount, List<TicketLine> lines) {

    public BookResult(String supplierBookingId, SupplierBookingStatus status, String pnr,
                      List<String> ticketNumbers, String message, BigDecimal amount) {
        this(supplierBookingId, status, pnr, ticketNumbers, message, amount, List.of());
    }
}
