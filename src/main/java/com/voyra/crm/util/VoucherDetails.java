package com.voyra.crm.util;

import com.voyra.crm.enums.BookingType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The travel details a voucher may carry, by booking type - the same flat keys the booking's own
 * fields use, so the screen reuses one form. Anything else, and blank values, are dropped.
 */
public final class VoucherDetails {

    private static final int MAX_LENGTH = 120;

    private static final Map<BookingType, List<String>> KEYS = Map.of(
            BookingType.FLIGHT, List.of("airline", "flightNumber", "flightFrom", "flightTo", "journeyDate", "returnDate",
                    "flightDepartTime", "flightArriveTime", "flightArriveDate", "flightCabin"),
            BookingType.HOTEL, List.of("hotelName", "hotelCity", "hotelCheckIn", "hotelCheckOut", "hotelRooms",
                    "hotelRoomType", "hotelBoardBasis"),
            BookingType.VISA, List.of("visaCountry", "journeyDate", "visaAppointmentDate", "visaAppliedDate", "visaIssuedDate"),
            BookingType.TRANSFER, List.of("transferPickup", "transferDropoff", "transferDate", "transferTime", "transferVehicleType"));
    private static final List<String> OTHER = List.of("destination", "journeyDate", "returnDate");

    /** The keys whose value is the last day of what the voucher covers. */
    private static final List<String> END_DATES = List.of("returnDate", "hotelCheckOut", "flightArriveDate", "transferDate", "journeyDate");

    private VoucherDetails() {
    }

    public static Map<String, String> clean(BookingType type, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : KEYS.getOrDefault(type, OTHER)) {
            String value = raw.get(key);
            if (value != null && !value.isBlank()) {
                String v = value.trim();
                out.put(key, v.length() > MAX_LENGTH ? v.substring(0, MAX_LENGTH) : v);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** The latest date a voucher's details mention, or null. */
    public static LocalDate lastDate(Map<String, String> details) {
        if (details == null) {
            return null;
        }
        return END_DATES.stream().map(details::get).filter(Objects::nonNull).map(VoucherDetails::date)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
