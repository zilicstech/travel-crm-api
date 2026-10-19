package com.voyra.crm.util;

import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.enums.BookingType;

import java.time.LocalDate;
import java.util.List;

/**
 * The trip-detail rules a booking and a lead's service share - one place, so the Bookings screen,
 * the lead wizard and the API refuse the same impossible trips. Each check skips a value that is
 * not given (both are partial-update shapes); a broken one is an {@link IllegalArgumentException}.
 */
public final class TripRules {

    private static final String CLOCK = "([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?";

    private TripRules() {
    }

    public static void stay(LocalDate checkIn, LocalDate checkOut, Integer rooms, Integer nights) {
        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Check-out must be after check-in");
        }
        atLeastOne(rooms, "Rooms");
        atLeastOne(nights, "Nights");
    }

    public static void flight(LocalDate departure, LocalDate returnDate) {
        if (departure != null && returnDate != null && returnDate.isBefore(departure)) {
            throw new IllegalArgumentException("The return date can't be before the departure date");
        }
    }

    /** A booking's start and end: a package reads as start/end, everything else as departure/return. */
    public static void journey(BookingType type, LocalDate start, LocalDate end) {
        if (type == BookingType.PACKAGE && start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("The end date can't be before the start date");
        }
        flight(start, end);
    }

    /** Legs fly in order: no sector departs before the one before it. */
    public static void sectors(List<FlightSectorDto> sectors) {
        LocalDate previous = null;
        for (FlightSectorDto sector : sectors == null ? List.<FlightSectorDto>of() : sectors) {
            if (sector == null || sector.getDate() == null) continue;
            if (previous != null && sector.getDate().isBefore(previous)) {
                throw new IllegalArgumentException("A flight leg can't be before the leg before it");
            }
            previous = sector.getDate();
        }
    }

    public static void transfer(String time, Integer passengers) {
        if (time != null && !time.isBlank() && !time.trim().matches(CLOCK)) {
            throw new IllegalArgumentException("Transfer time must be a time of day (HH:mm)");
        }
        atLeastOne(passengers, "Passengers");
    }

    private static void atLeastOne(Integer value, String field) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(field + " must be at least 1");
        }
    }
}
