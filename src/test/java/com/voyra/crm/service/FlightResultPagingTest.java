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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlightResultPagingTest {

    private static final Set<String> NONE = Set.of();

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
        assertThat(first.getKey()).isEqualTo("IN|61|2026-12-01T06:00|T08:00|ONWARD");
        assertThat(first.getDirection()).isEqualTo(FlightDirection.ONWARD);
        assertThat(first.getFares()).hasSize(2);
        FlightGroupResponse summary = FlightResultPaging.summarise(first, first.getFares(), NONE);
        assertThat(summary.getFares()).extracting(FlightOfferResponse::getPrice).containsExactly(new BigDecimal("4000"), new BigDecimal("5000"));
        assertThat(summary.getCheapestPrice()).isEqualByComparingTo("4000");
        assertThat(first.getCheapestPrice()).isNull();
        assertThat(groups.get(1).getKey()).endsWith("|RETURN");
        assertThat(groups.get(2).getKey()).isEqualTo("IN|61|T|T/IN|62|2026-12-05T09:00|T|COMBO");
        assertThat(groups.get(3).getKey()).endsWith("|COMBO");
    }

    private static FlightOfferResponse supplied(String supplierId, String supplierName, String code, String number, String displayed,
                                                boolean bookable, boolean refundable, FlightDirection direction) {
        FlightOfferResponse o = offer("IndiGo", number, 6, displayed, 0, "2h", refundable, direction);
        o.setAirlineCode(code);
        o.setSupplierId(supplierId);
        o.setSupplierName(supplierName);
        o.setDisplayedFare(new BigDecimal(displayed));
        o.setBookable(bookable);
        return o;
    }

    @Test
    void twoProviderShapesOfOneFlightGroupIntoOneRow() {
        FlightOfferResponse mock = supplied("s1", "A", "6E", "6E-206", "5000", true, false, null);
        FlightOfferResponse tripjack = supplied("s2", "B", "6e", "206", "5100", true, false, null);
        FlightOfferResponse third = supplied("s3", "C", "6E", "6E 0206", "5200", true, false, null);
        tripjack.setDepartTime(LocalTime.of(6, 0, 30));

        assertThat(FlightResultPaging.group(List.of(mock, tripjack, third))).hasSize(1).first()
                .satisfies(g -> assertThat(g.getFares()).hasSize(3));
        FlightOfferResponse noCode = supplied("s4", "D", " ", "000", "5300", true, false, null);
        noCode.setAirline(null);
        noCode.setFlightNumber(null);
        assertThat(FlightResultPaging.groupKey(noCode)).startsWith("|").contains("||");
        assertThat(FlightResultPaging.groupKey(supplied("s1", "A", "6E", "6E000", "1", true, false, null))).startsWith("6E|0|");
    }

    @Test
    void aDifferentDayOrDirectionIsAnotherRow() {
        FlightOfferResponse base = supplied("s1", "A", "6E", "206", "5000", true, false, null);
        FlightOfferResponse nextDay = supplied("s2", "B", "6E", "206", "5000", true, false, null);
        nextDay.setDepartDate(LocalDate.of(2026, 12, 2));
        FlightOfferResponse back = supplied("s2", "B", "6E", "206", "5000", true, false, FlightDirection.RETURN);

        assertThat(FlightResultPaging.group(List.of(base, nextDay, back))).hasSize(3);
    }

    @Test
    void theRowLeadsWithTheCheapestFareOfASupplierTakingBookings() {
        FlightOfferResponse s1 = supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null);
        FlightOfferResponse s2 = supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, true, null);
        FlightOfferResponse s2b = supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, true, null);
        s2b.setBookable(false);
        FlightGroupResponse group = FlightResultPaging.group(List.of(s1, s2, s2b)).get(0);

        FlightGroupResponse g = FlightResultPaging.summarise(group, group.getFares(), Set.of("s2"));

        assertThat(g.getCheapestSupplierName()).isEqualTo("Mock supplier 2");
        assertThat(g.getCheapestPrice()).isEqualByComparingTo("5200");
        assertThat(g.getSupplierCount()).isEqualTo(2);
        assertThat(g.getFares()).containsExactly(s2, s1, s2b);
        FlightOfferResponse tied = supplied("s3", null, "6E", "206", "5000", true, false, null);
        tied.setTotalPrice(new BigDecimal("5000"));
        tied.setDisplayedFare(null);
        assertThat(FlightResultPaging.summarise(group, List.of(s1, tied), Set.of()).getFares()).containsExactly(s1, tied);
    }

    @Test
    void everyFilteredCopyRecomputesTheSummary() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null),
                supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, true, null)));
        FlightSearchPageRequest r = req();
        r.setRefundableOnly(true);

        FlightGroupResponse g = FlightResultPaging.page(groups, r, Set.of("s1", "s2")).getFlights().get(0);

        assertThat(g.getSupplierCount()).isEqualTo(1);
        assertThat(g.getCheapestSupplierName()).isEqualTo("Mock supplier 2");
        assertThat(groups.get(0).getFares()).hasSize(2);
    }

    @Test
    void theSuppliersFilterKeepsOnlyThoseSuppliers() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null),
                supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, false, null),
                supplied("s1", "Mock supplier", "AI", "101", "4000", true, false, null)));
        FlightSearchPageRequest r = req();
        r.setSuppliers(List.of("s2"));

        FlightSearchPageResponse page = FlightResultPaging.page(groups, r, NONE);

        assertThat(page.getFlights()).singleElement().satisfies(g -> assertThat(g.getFares())
                .extracting(FlightOfferResponse::getSupplierId).containsOnly("s2"));
        r.setSuppliers(List.of());
        assertThat(FlightResultPaging.page(groups, r, NONE).getFlights()).hasSize(2);
    }

    @Test
    void thePageIsComboOnlyWhenEverySupplierAnsweredCombo() {
        FlightSearchPageRequest r = req();
        r.getQuery().setReturnDate(LocalDate.of(2026, 12, 5));
        FlightOfferResponse combo = supplied("s1", "Mock supplier", "6E", "206", "9000", true, false, FlightDirection.COMBO);
        FlightOfferResponse onward = supplied("s2", "Mock supplier 2", "AI", "101", "4000", true, false, null);
        FlightOfferResponse ret = supplied("s2", "Mock supplier 2", "AI", "102", "4100", true, false, FlightDirection.RETURN);

        FlightSearchPageResponse split = FlightResultPaging.page(FlightResultPaging.group(List.of(combo, onward, ret)), r, NONE);
        FlightSearchPageResponse all = FlightResultPaging.page(FlightResultPaging.group(List.of(combo)), r, NONE);

        assertThat(split.getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_SPLIT);
        assertThat(split.getFlights()).hasSize(2).anyMatch(g -> g.getDirection() == FlightDirection.COMBO);
        assertThat(split.getReturnFlights()).hasSize(1);
        assertThat(all.getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_COMBO);
    }

    @Test
    void thePageListsTheSuppliersInTheResultWithTheirBookingState() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, false, null),
                supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null),
                supplied("s1", "Mock supplier", "AI", "101", "4000", true, false, null)));

        assertThat(FlightResultPaging.page(groups, req(), Set.of("s2")).getSuppliers())
                .extracting("supplierId", "supplierName", "bookingEnabled")
                .containsExactly(org.assertj.core.api.Assertions.tuple("s1", "Mock supplier", false),
                        org.assertj.core.api.Assertions.tuple("s2", "Mock supplier 2", true));
    }

    @Test
    void aCachedGroupIsNeverMutated() {
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null),
                supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, true, null)));
        FlightSearchPageRequest refundable = req();
        refundable.setRefundableOnly(true);
        FlightSearchPageRequest second = req();
        second.setSuppliers(List.of("s1"));

        FlightResultPaging.page(groups, refundable, NONE);
        FlightResultPaging.page(groups, second, Set.of("s1"));

        assertThat(groups.get(0).getFares()).extracting(FlightOfferResponse::getSupplierId).containsExactly("s1", "s2");
        assertThat(groups.get(0).getCheapestPrice()).isNull();
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

        FlightSearchPageResponse page = FlightResultPaging.page(groups, r, NONE);

        assertThat(page.getTripType()).isEqualTo(FlightSearchTripType.ONE_WAY);
        assertThat(page.getTotalFlights()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getFlights()).extracting(FlightGroupResponse::getAirline).containsExactly("Vistara");
        assertThat(page.getAirlines()).containsExactly("Air India", "IndiGo", "Vistara");
        assertThat(page.getAirlineCodes()).containsOnlyKeys("Vistara");
        assertThat(page.getCheapestKey()).startsWith("IN|61|");
        assertThat(page.getFastestKey()).startsWith("VI|");
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
        FlightSearchPageResponse a = FlightResultPaging.page(groups, nonStopRefundable, NONE);
        assertThat(a.getFlights()).singleElement().satisfies(g -> {
            assertThat(g.getFlightNumber()).isEqualTo("6E1");
            assertThat(g.getCheapestPrice()).isEqualByComparingTo("4800");
            assertThat(g.getFares()).hasSize(1);
        });
        assertThat(groups.get(0).getFares()).hasSize(2);

        FlightSearchPageRequest twoPlus = req();
        twoPlus.setStops(2);
        twoPlus.setAirlines(List.of());
        assertThat(FlightResultPaging.page(groups, twoPlus, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("UK1", "UK3");

        FlightSearchPageRequest vistara = req();
        vistara.setAirlines(List.of("Vistara"));
        assertThat(FlightResultPaging.page(groups, vistara, NONE).getFlights()).extracting(FlightGroupResponse::getAirline)
                .containsOnly("Vistara");

        FlightSearchPageRequest oneStop = req();
        oneStop.setStops(1);
        assertThat(FlightResultPaging.page(groups, oneStop, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
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

        assertThat(FlightResultPaging.page(groups, byDuration, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("X3", "X2", "X1");
        assertThat(FlightResultPaging.page(groups, byDeparture, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("X2", "X1", "X3");
    }

    @Test
    void aSplitRoundTripCarriesItsReturnFlightsAndAComboIsReportedAsSuch() {
        List<FlightGroupResponse> split = FlightResultPaging.group(List.of(
                offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, null),
                offer("IndiGo", "6E2", 18, "4200", 0, "2h", false, FlightDirection.RETURN)));
        FlightSearchPageRequest r = req();
        r.getQuery().setReturnDate(LocalDate.of(2026, 12, 5));

        FlightSearchPageResponse page = FlightResultPaging.page(split, r, NONE);

        assertThat(page.getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_SPLIT);
        assertThat(page.getFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E1");
        assertThat(page.getReturnFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E2");

        List<FlightGroupResponse> combo = FlightResultPaging.group(List.of(offer("IndiGo", "6E1", 6, "8000", 0, "2h", false, FlightDirection.COMBO)));
        assertThat(FlightResultPaging.page(combo, r, NONE).getTripType()).isEqualTo(FlightSearchTripType.ROUND_TRIP_COMBO);
    }

    @Test
    void anEmptyResultIsOneEmptyPageEvenWithoutAQuery() {
        FlightSearchPageRequest r = new FlightSearchPageRequest();
        r.setPage(0);

        FlightSearchPageResponse page = FlightResultPaging.page(List.of(), r, NONE);

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
