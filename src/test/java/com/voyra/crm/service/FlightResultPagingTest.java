package com.voyra.crm.service;

import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightLegResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FlightDirection;
import com.voyra.crm.enums.FlightSearchTripType;
import com.voyra.crm.enums.FlightSortOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightResultPagingTest {

    private static FlightOfferResponse offer(String airline, String number, int departHour, String price, int stops,
                                             String duration, boolean refundable, FlightDirection direction) {
        return FlightOfferResponse.builder().offerId(number + "-" + price).airline(airline).airlineCode(airline.substring(0, 2).toUpperCase())
                .flightNumber(number).origin("BOM").destination("DEL")
                .departTime(LocalTime.of(departHour, 0)).arriveTime(LocalTime.of(departHour + 2, 0))
                .departDate(LocalDate.of(2026, 12, 1)).duration(duration).stops(stops)
                .price(new BigDecimal(price)).refundable(refundable).direction(direction).build();
    }

    private static FlightSearchPageRequest req() {
        FlightSearchPageRequest r = new FlightSearchPageRequest();
        r.setQuery(new FlightSearchQuery());
        return r;
    }

    @Test
    void faresOfOneFlightAreGroupedCheapestFirstAndDirectionsAndReturnLegsSplitGroups() {
        FlightOfferResponse dear = offer("IndiGo", "6E1", 6, "5000", 0, "2h", true, null);
        FlightOfferResponse cheap = offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, FlightDirection.ONWARD);
        FlightOfferResponse back = offer("IndiGo", "6E1", 6, "4500", 0, "2h", false, FlightDirection.RETURN);
        FlightLegResponse out = FlightLegResponse.builder().flightNumber("6E1").build();
        FlightLegResponse ret = FlightLegResponse.builder().flightNumber("6E2").departDate(LocalDate.of(2026, 12, 5))
                .departTime(LocalTime.of(9, 0)).build();
        FlightOfferResponse combo = offer("IndiGo", "6E1", 6, "9000", 0, "2h", false, FlightDirection.COMBO);
        combo.setLegs(List.of(out, ret));
        FlightOfferResponse oneLeg = offer("IndiGo", "6E1", 6, "8000", 0, "2h", false, FlightDirection.COMBO);
        oneLeg.setLegs(List.of(out));

        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(dear, cheap, back, combo, oneLeg));

        assertThat(groups).hasSize(4);
        FlightGroupResponse first = groups.get(0);
        assertThat(first.getKey()).isEqualTo("IndiGo|6E1|06:00|08:00");
        assertThat(first.getDirection()).isEqualTo(FlightDirection.ONWARD);
        assertThat(first.getFares()).extracting(FlightOfferResponse::getPrice).containsExactly(new BigDecimal("4000"), new BigDecimal("5000"));
        assertThat(first.getCheapestPrice()).isEqualByComparingTo("4000");
        assertThat(groups.get(1).getKey()).isEqualTo("IndiGo|6E1|06:00|08:00|RETURN");
        assertThat(groups.get(2).getKey()).isEqualTo("IndiGo|6E1|06:00|08:00|COMBO|6E2|2026-12-05|09:00");
        assertThat(groups.get(3).getKey()).isEqualTo("IndiGo|6E1|06:00|08:00|COMBO");
    }

    @Test
    void aOneWayPageListsAirlinesCheapestAndFastestAndClampsThePage() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                offer("Vistara", "UK1", 7, "6000", 0, "1h 50m", true, null),
                offer("IndiGo", "6E1", 6, "4000", 1, "3h", false, null),
                offer("Air India", "AI1", 8, "5000", 2, "4h 10m", true, null)));
        groups.get(2).setAirlineCode(" ");
        groups.get(1).setAirlineCode(null);
        FlightSearchPageRequest r = req();
        r.setPageSize(2);
        r.setPage(9);

        FlightSearchPageResponse page = FlightResultPaging.page(groups, r);

        assertThat(page.getTripType()).isEqualTo(FlightSearchTripType.ONE_WAY);
        assertThat(page.getTotalFlights()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getFlights()).extracting(FlightGroupResponse::getAirline).containsExactly("Vistara");
        assertThat(page.getAirlines()).containsExactly("Air India", "IndiGo", "Vistara");
        assertThat(page.getAirlineCodes()).containsOnlyKeys("Vistara");
        assertThat(page.getCheapestKey()).startsWith("IndiGo");
        assertThat(page.getFastestKey()).startsWith("Vistara");
        assertThat(page.getReturnFlights()).isEmpty();
    }

    @Test
    void stopsAirlinesAndRefundableFiltersApplyWithoutTouchingTheCachedGroup() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, null),
                offer("IndiGo", "6E1", 6, "4800", 0, "2h", true, null),
                offer("IndiGo", "6E3", 9, "3000", 0, "2h", false, null),
                offer("IndiGo", "6E5", 10, "3500", 1, "4h", true, null),
                offer("Vistara", "UK1", 7, "6000", 2, "6h", true, null),
                offer("Vistara", "UK3", 11, "6500", 3, "7h", true, null)));

        FlightSearchPageRequest nonStopRefundable = req();
        nonStopRefundable.setStops(0);
        nonStopRefundable.setRefundableOnly(true);
        nonStopRefundable.setAirlines(List.of("IndiGo"));
        FlightSearchPageResponse a = FlightResultPaging.page(groups, nonStopRefundable);
        assertThat(a.getFlights()).singleElement().satisfies(g -> {
            assertThat(g.getFlightNumber()).isEqualTo("6E1");
            assertThat(g.getCheapestPrice()).isEqualByComparingTo("4800");
            assertThat(g.getFares()).hasSize(1);
        });
        assertThat(groups.get(0).getFares()).hasSize(2);

        FlightSearchPageRequest twoPlus = req();
        twoPlus.setStops(2);
        twoPlus.setAirlines(List.of());
        assertThat(FlightResultPaging.page(groups, twoPlus).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("UK1", "UK3");

        FlightSearchPageRequest vistara = req();
        vistara.setAirlines(List.of("Vistara"));
        assertThat(FlightResultPaging.page(groups, vistara).getFlights()).extracting(FlightGroupResponse::getAirline)
                .containsOnly("Vistara");

        FlightSearchPageRequest oneStop = req();
        oneStop.setStops(1);
        assertThat(FlightResultPaging.page(groups, oneStop).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("6E5");
    }

    @Test
    void durationAndDepartureSortsBreakTiesByPrice() {
        List<FlightGroupResponse> groups = new ArrayList<>(FlightResultPaging.group(List.of(
                offer("A1", "X1", 9, "300", 0, "2h", false, null),
                offer("A2", "X2", 6, "200", 0, "2h", false, null),
                offer("A3", "X3", 6, "100", 0, "90", false, null))));
        groups.get(2).setDepartTime(null);

        FlightSearchPageRequest byDuration = req();
        byDuration.setSort(FlightSortOrder.DURATION);
        FlightSearchPageRequest byDeparture = req();
        byDeparture.setSort(FlightSortOrder.DEPARTURE);

        assertThat(FlightResultPaging.page(groups, byDuration).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("X3", "X2", "X1");
        assertThat(FlightResultPaging.page(groups, byDeparture).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("X2", "X1", "X3");
    }

    @Test
    void aSplitRoundTripCarriesItsReturnFlightsAndAComboIsReportedAsSuch() {
        List<FlightGroupResponse> split = FlightResultPaging.group(List.of(
                offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, null),
                offer("IndiGo", "6E2", 18, "4200", 0, "2h", false, FlightDirection.RETURN)));
        FlightSearchPageRequest r = req();
        r.getQuery().setReturnDate(LocalDate.of(2026, 12, 5));

        FlightSearchPageResponse page = FlightResultPaging.page(split, r);

        assertThat(page.getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_SPLIT);
        assertThat(page.getFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E1");
        assertThat(page.getReturnFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E2");

        List<FlightGroupResponse> combo = FlightResultPaging.group(List.of(offer("IndiGo", "6E1", 6, "8000", 0, "2h", false, FlightDirection.COMBO)));
        assertThat(FlightResultPaging.page(combo, r).getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_COMBO);
    }

    @Test
    void anEmptyResultIsOneEmptyPageEvenWithoutAQuery() {
        FlightSearchPageRequest r = new FlightSearchPageRequest();
        r.setPage(0);

        FlightSearchPageResponse page = FlightResultPaging.page(List.of(), r);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getFlights()).isEmpty();
        assertThat(page.getCheapestKey()).isNull();
        assertThat(page.getFastestKey()).isNull();
        assertThat(page.getTripType()).isEqualTo(FlightSearchTripType.ONE_WAY);
    }

    @Test
    void durationsParseMinutesHoursAndMixedFormsAndUnknownSortsLast() {
        assertThat(FlightResultPaging.durationMinutes("140")).isEqualTo(140);
        assertThat(FlightResultPaging.durationMinutes(" 3h 05m ")).isEqualTo(185);
        assertThat(FlightResultPaging.durationMinutes("2H")).isEqualTo(120);
        assertThat(FlightResultPaging.durationMinutes("45m")).isEqualTo(45);
        assertThat(FlightResultPaging.durationMinutes("soon")).isEqualTo(Integer.MAX_VALUE);
        assertThat(FlightResultPaging.durationMinutes(" ")).isEqualTo(Integer.MAX_VALUE);
        assertThat(FlightResultPaging.durationMinutes(null)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void zeroIfNullDefaultsAMissingAmount() {
        assertThat(FlightResultPaging.zeroIfNull(null)).isEqualByComparingTo("0");
        assertThat(FlightResultPaging.zeroIfNull(BigDecimal.TEN)).isEqualByComparingTo("10");
    }
}
