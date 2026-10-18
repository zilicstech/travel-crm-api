package com.voyra.crm.util;

import com.voyra.crm.enums.BookingType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherDetailsTest {

    @Test
    void keepsOnlyTheBookingTypesKeysAndDropsBlanks() {
        Map<String, String> raw = new HashMap<>();
        raw.put("airline", " IndiGo ");
        raw.put("flightNumber", "6E 203");
        raw.put("hotelName", "Not a flight field");
        raw.put("flightCabin", "  ");
        raw.put("sellingPrice", "1");

        assertThat(VoucherDetails.clean(BookingType.FLIGHT, raw)).containsExactly(
                Map.entry("airline", "IndiGo"), Map.entry("flightNumber", "6E 203"));
        assertThat(VoucherDetails.clean(BookingType.HOTEL, raw)).containsExactly(Map.entry("hotelName", "Not a flight field"));
        assertThat(VoucherDetails.clean(BookingType.VISA, Map.of("airline", "x"))).isNull();
    }

    @Test
    void theLastDateIsTheLatestOfTheVouchersDates() {
        assertThat(VoucherDetails.lastDate(Map.of("journeyDate", "2026-12-20", "flightArriveDate", "2026-12-21")))
                .isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(VoucherDetails.lastDate(Map.of("journeyDate", "not a date"))).isNull();
        assertThat(VoucherDetails.lastDate(null)).isNull();
    }
}
