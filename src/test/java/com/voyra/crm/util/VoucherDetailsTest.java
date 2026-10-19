package com.voyra.crm.util;

import com.voyra.crm.enums.BookingType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void theTripRulesApplyToAVouchersDetails() {
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.HOTEL,
                Map.of("hotelCheckIn", "2026-12-12", "hotelCheckOut", "2026-12-11"))).hasMessageContaining("Check-out");
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.HOTEL, Map.of("hotelRooms", "0")))
                .hasMessageContaining("Rooms must be at least 1");
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.HOTEL, Map.of("hotelRooms", "two")))
                .hasMessageContaining("Rooms");
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.TRANSFER, Map.of("transferTime", "25:99")))
                .hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.FLIGHT,
                Map.of("journeyDate", "2026-12-20", "returnDate", "2026-12-10"))).hasMessageContaining("return");
        assertThatThrownBy(() -> VoucherDetails.clean(BookingType.PACKAGE,
                Map.of("journeyDate", "2026-12-20", "returnDate", "2026-12-10"))).hasMessageContaining("end date");
        assertThat(VoucherDetails.clean(BookingType.HOTEL,
                Map.of("hotelCheckIn", "2026-12-12", "hotelCheckOut", "2026-12-15", "hotelRooms", "1"))).hasSize(3);
    }
}
