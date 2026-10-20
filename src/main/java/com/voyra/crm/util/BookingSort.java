package com.voyra.crm.util;

import org.springframework.data.domain.Sort;

import java.util.Map;

/** Whitelists the Bookings screen's sortable columns so a request can't sort on arbitrary fields. */
public final class BookingSort {

    private static final Map<String, String> COLUMNS = Map.of(
            "bookingNumber", "bookingNumber",
            "clientName", "clientName",
            "sellingPrice", "sellingPrice",
            "bookingDate", "bookingDate",
            // "Travel date": each type's own first date (flight departure, check-in, transfer day).
            "journeyDate", "departureDate",
            "agentName", "agentName",
            "supplier", "supplier",
            "netCost", "netCost");

    private BookingSort() {
    }

    /** "journeyDate,asc" -> travel date ascending; blank -> newest booking number first. */
    public static Sort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "bookingNumber");
        }
        String[] parts = raw.split(",");
        String column = COLUMNS.get(parts[0].trim());
        if (column == null) {
            throw new IllegalArgumentException("Cannot sort bookings by " + parts[0].trim());
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Ties (same date, same client) fall back to the booking number so paging is stable.
        Sort sort = Sort.by(dir, column);
        if (column.equals("departureDate")) {
            // Spring Data drops nullsLast() on criteria queries; a computed "has no date" flag does the job.
            sort = Sort.by(Sort.Direction.ASC, "noJourneyDate").and(sort);
        }
        return column.equals("bookingNumber") ? sort : sort.and(Sort.by(Sort.Direction.DESC, "bookingNumber"));
    }
}
