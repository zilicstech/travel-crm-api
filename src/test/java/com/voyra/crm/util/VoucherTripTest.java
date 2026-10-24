package com.voyra.crm.util;

import com.voyra.crm.dto.VoucherLeg;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoucherTripTest {

    private static VoucherLeg leg(String from, String to, String date) {
        return VoucherLeg.builder().from(from).to(to).travelDate(date == null ? null : LocalDate.parse(date)).build();
    }

    @Test
    void aTicketNeedsItsTravellersAndAFlight() {
        assertThatThrownBy(() -> VoucherTrip.check(0, List.of(leg("DEL", "BOM", "2026-12-01"))))
                .hasMessage("Add the travellers on this ticket");
        assertThatThrownBy(() -> VoucherTrip.check(1, List.of())).hasMessage("Add the flight - from, to and the travel date");
    }

    @Test
    void blankRowsDropAndAHalfFilledFlightIsRefused() {
        assertThat(VoucherTrip.cleanLegs(List.of(leg(" dla ", "add", "2026-10-31"), leg(null, null, null))))
                .extracting(VoucherLeg::getFrom).containsExactly("DLA");
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(leg("DLA", null, "2026-10-31"))))
                .hasMessage("Flight 1 needs from, to and the travel date");
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(leg("DLA", "ADD", "2026-10-31"), leg("ADD", "BOM", "2026-10-30"))))
                .hasMessage("Flight 2 can't be before the one it connects from");
    }

    @Test
    void theFlatDetailsRunFromTheFirstFlightToTheLast() {
        List<VoucherLeg> legs = List.of(
                VoucherLeg.builder().from("DLA").to("ADD").airline("Ethiopian").flightNumber("ET 962").cabin("Economy")
                        .travelDate(LocalDate.parse("2026-10-31")).departTime("13:45").build(),
                VoucherLeg.builder().from("ADD").to("BOM").flightNumber("ET 640")
                        .travelDate(LocalDate.parse("2026-11-01")).arriveTime("06:10").build());

        Map<String, String> d = VoucherTrip.details(legs, Map.of("returnDate", "2026-11-10", "flightTo", "XXX"));

        assertThat(d).containsEntry("flightFrom", "DLA").containsEntry("flightTo", "BOM")
                .containsEntry("journeyDate", "2026-10-31").containsEntry("flightArriveDate", "2026-11-01")
                .containsEntry("airline", "Ethiopian").containsEntry("flightNumber", "ET 962")
                .containsEntry("flightDepartTime", "13:45").containsEntry("flightArriveTime", "06:10")
                .doesNotContainKey("returnDate");
        assertThat(VoucherDetails.lastDate(d)).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    void aReturnTripKeepsItsFarthestPointNotItsStartAgain() {
        List<VoucherLeg> legs = List.of(
                VoucherLeg.builder().from("DEL").to("BOM").travelDate(LocalDate.parse("2026-12-20")).build(),
                VoucherLeg.builder().from("BOM").to("DEL").travelDate(LocalDate.parse("2026-12-27")).build());

        Map<String, String> d = VoucherTrip.details(legs, null);

        assertThat(d).containsEntry("flightFrom", "DEL").containsEntry("flightTo", "BOM")
                .containsEntry("returnDate", "2026-12-27");
    }
}
