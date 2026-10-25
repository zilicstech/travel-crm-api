package com.voyra.crm.models.booking;

import java.util.List;

public record BookResult(String supplierBookingId, SupplierBookingStatus status, String pnr,
                         List<String> ticketNumbers, String message) {
}
