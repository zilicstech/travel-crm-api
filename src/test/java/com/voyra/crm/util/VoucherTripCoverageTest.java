package com.voyra.crm.util;

import com.voyra.crm.dto.VoucherLeg;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoucherTripCoverageTest {

    private static final LocalDate D1 = LocalDate.of(2026, 5, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 5, 3);

    private static VoucherLeg leg(String from, String to, LocalDate date) {
        return VoucherLeg.builder().from(from).to(to).travelDate(date).build();
    }

    @Test
    void noLegsOrNullRowsCleanToAnEmptyList() {
        assertThat(VoucherTrip.cleanLegs(null)).isEmpty();
        assertThat(VoucherTrip.cleanLegs(Arrays.asList(null, VoucherLeg.builder().build()))).isEmpty();
    }

    @Test
    void aRowWithOnlyOnePartIsNotBlankAndIsRefused() {
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(VoucherLeg.builder().to("BBB").build())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Flight 1 needs from, to and the travel date");
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(VoucherLeg.builder().travelDate(D1).build())))
                .hasMessage("Flight 1 needs from, to and the travel date");
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(VoucherLeg.builder().flightNumber("XX 1").build())))
                .hasMessage("Flight 1 needs from, to and the travel date");
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(leg("AAA", "BBB", null))))
                .hasMessage("Flight 1 needs from, to and the travel date");
    }

    @Test
    void aConnectingFlightCannotBeBeforeTheOneBefore() {
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(List.of(leg("AAA", "BBB", D2), leg("BBB", "CCC", D1))))
                .hasMessage("Flight 2 can't be before the one it connects from");
    }

    @Test
    void atMostEightFlights() {
        List<VoucherLeg> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(leg("AAA", "BBB", D1));
        List<VoucherLeg> eight = nine.subList(0, 8);

        assertThat(VoucherTrip.cleanLegs(eight)).hasSize(8);
        assertThatThrownBy(() -> VoucherTrip.cleanLegs(nine)).hasMessage("A ticket holds at most 8 flights");
    }

    @Test
    void longTextIsCutToSixtyCharactersAndCodesUpperCased() {
        String longName = "A".repeat(70);

        VoucherLeg c = VoucherTrip.cleanLegs(List.of(VoucherLeg.builder().from(" aaa ").to("bbb").travelDate(D1)
                .airline(longName).cabin(" ").build())).get(0);

        assertThat(c.getFrom()).isEqualTo("AAA");
        assertThat(c.getAirline()).hasSize(60);
        assertThat(c.getCabin()).isNull();
    }

    @Test
    void aTicketWithTravellersAndAFlightPasses() {
        assertThatCode(() -> VoucherTrip.check(1, List.of(leg("AAA", "BBB", D1)))).doesNotThrowAnyException();
    }

    @Test
    void detailsWithoutLegsKeepWhatWasThereOrNothing() {
        assertThat(VoucherTrip.details(List.of(), null)).isNull();
        assertThat(VoucherTrip.details(List.of(), Map.of())).isNull();
        assertThat(VoucherTrip.details(List.of(), Map.of("k", "v"))).containsExactly(Map.entry("k", "v"));
    }

    @Test
    void aSingleFlightHasNoReturnOrArrivalDateAndBlankFieldsAreLeftOut() {
        Map<String, String> old = new HashMap<>(Map.of("returnDate", "2026-01-01", "other", "x"));
        VoucherLeg one = VoucherLeg.builder().from("AAA").to("BBB").travelDate(D1).airline(" ").build();

        Map<String, String> d = VoucherTrip.details(List.of(one), old);

        assertThat(d).containsOnly(Map.entry("other", "x"), Map.entry("flightFrom", "AAA"), Map.entry("flightTo", "BBB"),
                Map.entry("journeyDate", "2026-05-01"));
    }

    @Test
    void aOneWayConnectionEndsAtTheLastAirport() {
        Map<String, String> d = VoucherTrip.details(List.of(leg("AAA", "BBB", D1), leg("BBB", "CCC", D1)), null);

        assertThat(d).containsEntry("flightTo", "CCC").doesNotContainKeys("returnDate", "flightArriveDate");
    }

    @Test
    void theFirstDateIsTheFirstFlightsOrNull() {
        assertThat(VoucherTrip.firstDate(null)).isNull();
        assertThat(VoucherTrip.firstDate(List.of())).isNull();
        assertThat(VoucherTrip.firstDate(List.of(leg("AAA", "BBB", D2)))).isEqualTo(D2);
    }
}
