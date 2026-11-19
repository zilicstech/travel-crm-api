package com.voyra.crm.service;

import com.voyra.crm.dto.FlightGroupResponse;
import com.voyra.crm.dto.FlightLegResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.BaggageFilter;
import com.voyra.crm.enums.FlightDirection;
import com.voyra.crm.enums.FlightRefundType;
import com.voyra.crm.enums.FlightSearchTripType;
import com.voyra.crm.enums.FlightSortOrder;
import com.voyra.crm.enums.TimeBucket;
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
    void aFareWithNoPriceAtAllSortsLastInsteadOfFailingThePage() {
        FlightOfferResponse priced = supplied("s1", "A", "6E", "206", "5000", true, false, null);
        FlightOfferResponse unpriced = supplied("s2", "B", "6E", "206", "5000", true, false, null);
        unpriced.setDisplayedFare(null);
        unpriced.setTotalPrice(null);
        unpriced.setPrice(null);
        FlightGroupResponse group = FlightResultPaging.group(List.of(unpriced, priced)).get(0);

        FlightGroupResponse g = FlightResultPaging.summarise(group, group.getFares(), Set.of());

        assertThat(g.getFares()).containsExactly(priced, unpriced);
        assertThat(g.getCheapestPrice()).isEqualByComparingTo("5000");
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
    void aLegWithOnlyAnAirlineNameKeepsItsOwnAndAnEmptyLegListFallsBackToTheOffer() {
        FlightOfferResponse named = offer("IndiGo", "6E1", 6, "5000", 0, "2h", true, FlightDirection.COMBO);
        named.setLegs(List.of(FlightLegResponse.builder().airline("Air India").flightNumber("101").build()));
        FlightOfferResponse empty = offer("IndiGo", "6E1", 6, "5000", 0, "2h", true, FlightDirection.ONWARD);
        empty.setLegs(List.of());

        assertThat(FlightResultPaging.groupKey(named)).startsWith("AIR INDIA|101|");
        assertThat(FlightResultPaging.groupKey(empty)).startsWith("IN|61|");
    }

    @Test
    void aBookableFareWithoutASupplierIdSortsAfterOneOnASupplierTakingBookings() {
        FlightOfferResponse orphan = supplied(null, null, "6E", "206", "4000", true, false, null);
        FlightOfferResponse taking = supplied("s1", "A", "6E", "206", "5000", true, false, null);
        FlightGroupResponse g = FlightResultPaging.group(List.of(orphan, taking)).get(0);

        assertThat(FlightResultPaging.summarise(g, g.getFares(), Set.of("s1")).getFares()).containsExactly(taking, orphan);
    }

    @Test
    void aRoundTripWithAComboBesideAnOnwardOrWithOnlyReturnsIsSplit() {
        FlightSearchPageRequest r = req();
        r.getQuery().setReturnDate(LocalDate.of(2026, 12, 5));
        FlightOfferResponse combo = supplied("s1", "A", "6E", "206", "9000", true, false, FlightDirection.COMBO);
        FlightOfferResponse onward = supplied("s2", "B", "AI", "101", "4000", true, false, null);
        FlightOfferResponse ret = supplied("s2", "B", "AI", "102", "4100", true, false, FlightDirection.RETURN);

        assertThat(FlightResultPaging.page(FlightResultPaging.group(List.of(combo, onward)), r, NONE).getTripType())
                .isEqualTo(FlightSearchTripType.ROUND_TRIP_SPLIT);
        assertThat(FlightResultPaging.page(FlightResultPaging.group(List.of(ret)), r, NONE).getTripType())
                .isEqualTo(FlightSearchTripType.ROUND_TRIP_SPLIT);
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
        // The cheaper fare is second, so an in-place sort would change the cached order.
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(
                supplied("s2", "Mock supplier 2", "6E", "206", "5200", true, true, null),
                supplied("s1", "Mock supplier", "6E", "206", "5000", true, false, null)));
        FlightSearchPageRequest refundable = req();
        refundable.setRefundableOnly(true);
        FlightSearchPageRequest second = req();
        second.setSuppliers(List.of("s1", "s2"));

        FlightResultPaging.page(groups, refundable, NONE);
        FlightGroupResponse led = FlightResultPaging.page(groups, second, Set.of("s1")).getFlights().get(0);

        assertThat(led.getFares()).extracting(FlightOfferResponse::getSupplierId).containsExactly("s1", "s2");
        assertThat(groups.get(0).getFares()).extracting(FlightOfferResponse::getSupplierId).containsExactly("s2", "s1");
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
                .containsExactly("UK1");
        twoPlus.setStops(3);
        assertThat(FlightResultPaging.page(groups, twoPlus, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("UK3");
        twoPlus.setStops(4); // anything from 3 up means three or more
        twoPlus.setAirlines(List.of("VI"));
        assertThat(FlightResultPaging.page(groups, twoPlus, NONE).getFlights()).extracting(FlightGroupResponse::getFlightNumber)
                .containsExactly("UK3");

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

    private static List<String> numbers(List<FlightGroupResponse> groups, java.util.function.Consumer<FlightSearchPageRequest> filter) {
        FlightSearchPageRequest r = req();
        filter.accept(r);
        return FlightResultPaging.page(groups, r, NONE).getFlights().stream().map(FlightGroupResponse::getFlightNumber).toList();
    }

    /** SG 671 05:00-07:00 DEL-BOM (three fares), 6E-204 13:00-15:00 DED-BOM, AI 9 19:00-21:00 DEL-NMI with no times on a second copy. */
    private static List<FlightGroupResponse> filterable() {
        FlightOfferResponse saver = offer("SpiceJet", "671", 5, "4000", 0, "2h", false, null);
        saver.setAirlineCode("SG");
        saver.setOrigin("DEL");
        saver.setDestination("BOM");
        saver.setFareType("Saver");
        saver.setRefundType(FlightRefundType.NON_REFUNDABLE);
        saver.setBaggage("Hand baggage only");
        FlightOfferResponse flexi = offer("SpiceJet", "671", 5, "5000", 0, "2h", true, null);
        flexi.setAirlineCode("SG");
        flexi.setOrigin("DEL");
        flexi.setDestination("BOM");
        flexi.setFareType("FLEXI");
        flexi.setRefundType(FlightRefundType.REFUNDABLE);
        flexi.setBaggage("15 Kg");
        FlightOfferResponse unpriced = offer("SpiceJet", "671", 5, "1", 0, "2h", true, null);
        unpriced.setAirlineCode("SG");
        unpriced.setOrigin("DEL");
        unpriced.setDestination("BOM");
        unpriced.setPrice(null);
        unpriced.setFareType(" ");
        unpriced.setBaggage("0 Kg");
        FlightOfferResponse indigo = offer("IndiGo", "6E-204", 13, "6000", 0, "2h", true, null);
        indigo.setAirlineCode("6E");
        indigo.setOrigin("DED");
        indigo.setDestination("BOM");
        indigo.setRefundType(FlightRefundType.PARTIAL);
        indigo.setLegs(List.of(FlightLegResponse.builder().airlineCode("6E").flightNumber("6E-204").build(),
                FlightLegResponse.builder().airlineCode("QP").flightNumber("1410").build()));
        FlightOfferResponse air = offer("Air India", "9", 19, "7000", 0, "2h", true, null);
        air.setAirlineCode("AI");
        air.setOrigin("DEL");
        air.setDestination("NMI");
        air.setDisplayedFare(new BigDecimal("6500"));
        FlightOfferResponse timeless = offer("Air India", "77", 20, "8000", 0, "2h", true, null);
        timeless.setAirlineCode(null);
        timeless.setDepartTime(null);
        timeless.setArriveTime(null);
        timeless.setOrigin(null);
        timeless.setDestination(" ");
        return FlightResultPaging.group(List.of(saver, flexi, unpriced, indigo, air, timeless));
    }

    @Test
    void timeAirportAndFlightNumberFiltersDropWholeFlights() {
        List<FlightGroupResponse> groups = filterable();

        assertThat(numbers(groups, r -> r.setDepartBuckets(List.of(TimeBucket.EARLY, TimeBucket.EVENING)))).containsExactly("671", "9");
        assertThat(numbers(groups, r -> r.setDepartBuckets(List.of()))).hasSize(4);
        assertThat(numbers(groups, r -> r.setArriveBuckets(List.of(TimeBucket.AFTERNOON)))).containsExactly("6E-204");
        assertThat(numbers(groups, r -> r.setArriveBuckets(List.of(TimeBucket.MORNING)))).containsExactly("671");
        assertThat(numbers(groups, r -> r.setOrigins(List.of("DED")))).containsExactly("6E-204");
        assertThat(numbers(groups, r -> r.setDestinations(List.of("NMI")))).containsExactly("9");
        assertThat(numbers(groups, r -> r.setFlightNumber("671"))).containsExactly("671");
        assertThat(numbers(groups, r -> r.setFlightNumber("sg 671"))).containsExactly("671");
        assertThat(numbers(groups, r -> r.setFlightNumber("6E-204"))).containsExactly("6E-204");
        assertThat(numbers(groups, r -> r.setFlightNumber("qp1410"))).containsExactly("6E-204"); // a later leg's number
        assertThat(numbers(groups, r -> r.setFlightNumber("999"))).isEmpty();
        assertThat(numbers(groups, r -> r.setFlightNumber(" - "))).hasSize(4);
        assertThat(TimeBucket.of(LocalTime.of(5, 59))).isEqualTo(TimeBucket.EARLY);
        assertThat(TimeBucket.of(LocalTime.of(6, 0))).isEqualTo(TimeBucket.MORNING);
        assertThat(TimeBucket.of(LocalTime.of(23, 59))).isEqualTo(TimeBucket.EVENING);
    }

    @Test
    void priceFareTypeRefundTypeAndBaggageFiltersDropFaresAndAFlightWithNoneLeft() {
        List<FlightGroupResponse> groups = filterable();

        FlightSearchPageRequest fromFive = req();
        fromFive.setMinPrice(new BigDecimal("5000"));
        assertThat(FlightResultPaging.page(groups, fromFive, NONE).getFlights()).first()
                .satisfies(g -> assertThat(g.getFares()).extracting(FlightOfferResponse::getFareType).containsExactly("FLEXI"));
        // the displayed fare (6500), not the supplier's price (7000), is what a price filter reads
        assertThat(numbers(groups, r -> r.setMaxPrice(new BigDecimal("6500")))).containsExactly("671", "6E-204", "9");
        assertThat(numbers(groups, r -> {
            r.setMinPrice(new BigDecimal("4500"));
            r.setMaxPrice(new BigDecimal("5500"));
        })).containsExactly("671");
        assertThat(numbers(groups, r -> r.setFareTypes(List.of("flexi")))).containsExactly("671");
        assertThat(numbers(groups, r -> r.setFareTypes(List.of()))).hasSize(4);
        assertThat(numbers(groups, r -> r.setRefundTypes(List.of(FlightRefundType.PARTIAL, FlightRefundType.NON_REFUNDABLE))))
                .containsExactly("671", "6E-204");

        FlightSearchPageRequest handOnly = req();
        handOnly.setBaggage(BaggageFilter.HAND_ONLY);
        // hand-baggage wording, "0 Kg" and no allowance at all are hand only
        assertThat(FlightResultPaging.page(groups, handOnly, NONE).getFlights()).extracting(g -> g.getFares().size()).containsExactly(2, 1, 1, 1);
        assertThat(numbers(groups, r -> r.setBaggage(BaggageFilter.CHECK_IN))).containsExactly("671");
        assertThat(FlightResultPaging.hasCheckIn(null)).isFalse();
    }

    @Test
    void thePageCarriesPriceFareTypeAndAirportFacetsOfTheUnfilteredResult() {
        FlightSearchPageRequest r = req();
        r.setStops(1); // filters nothing in, so the facets below are not from the filtered list

        FlightSearchPageResponse page = FlightResultPaging.page(filterable(), r, NONE);

        assertThat(page.getFlights()).isEmpty();
        assertThat(page.getPriceMin()).isEqualByComparingTo("4000");
        assertThat(page.getPriceMax()).isEqualByComparingTo("8000");
        assertThat(page.getFareTypes()).containsExactly("FLEXI", "Saver");
        assertThat(page.getOrigins()).containsExactly("DED", "DEL");
        assertThat(page.getDestinations()).containsExactly("BOM", "NMI");
        FlightSearchPageResponse empty = FlightResultPaging.page(List.of(), req(), NONE);
        assertThat(empty.getPriceMin()).isNull();
        assertThat(empty.getPriceMax()).isNull();
    }

    @Test
    void fareTypesAreGroupedByCabinWithTheFlightsOfEachAndFilterByIt() {
        FlightOfferResponse ecoFlex = offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, FlightDirection.ONWARD);
        ecoFlex.setFareType("Eco Flex");
        ecoFlex.setCabin(com.voyra.crm.enums.FlightCabin.ECONOMY);
        FlightOfferResponse business = offer("IndiGo", "6E1", 6, "19000", 0, "2h", true, FlightDirection.ONWARD);
        business.setOfferId("6E1-biz");
        business.setFareType("Business Flex");
        business.setCabin(com.voyra.crm.enums.FlightCabin.BUSINESS);
        FlightOfferResponse saver = offer("Vistara", "UK9", 9, "4500", 0, "2h", false, FlightDirection.ONWARD);
        saver.setFareType("Economy Saver");
        saver.setCabin(com.voyra.crm.enums.FlightCabin.ECONOMY);
        List<FlightGroupResponse> groups = FlightResultPaging.group(List.of(ecoFlex, business, saver));

        FlightSearchPageResponse all = FlightResultPaging.page(groups, req(), NONE);
        assertThat(all.getFareCabins()).containsExactly(
                new com.voyra.crm.dto.FareCabinCount(com.voyra.crm.enums.FlightCabin.ECONOMY, 2),
                new com.voyra.crm.dto.FareCabinCount(com.voyra.crm.enums.FlightCabin.BUSINESS, 1));

        FlightSearchPageRequest r = req();
        r.setFareCabins(List.of(com.voyra.crm.enums.FlightCabin.BUSINESS));
        FlightSearchPageResponse biz = FlightResultPaging.page(groups, r, NONE);
        assertThat(biz.getFlights()).hasSize(1).first().satisfies(g -> assertThat(g.getFares()).extracting(FlightOfferResponse::getFareType)
                .containsExactly("Business Flex"));
        assertThat(biz.getFareCabins()).hasSize(2); // facets come from the unfiltered result
    }

    @Test
    void onTheWayBackTheAirportFiltersAreReadTheOtherWayRound() {
        FlightOfferResponse out = offer("IndiGo", "6E1", 6, "4000", 0, "2h", false, FlightDirection.ONWARD);
        out.setOrigin("DEL");
        out.setDestination("BOM");
        FlightOfferResponse back = offer("IndiGo", "6E2", 9, "4000", 0, "2h", false, FlightDirection.RETURN);
        back.setOrigin("BOM");
        back.setDestination("DEL");
        FlightOfferResponse backToHindon = offer("IndiGo", "6E4", 9, "4000", 0, "2h", false, FlightDirection.RETURN);
        backToHindon.setOrigin("NMI");
        backToHindon.setDestination("HDO");
        FlightSearchPageRequest r = req();
        r.getQuery().setReturnDate(LocalDate.of(2026, 12, 5));
        r.setOrigins(List.of("DEL"));
        r.setDestinations(List.of("BOM"));

        FlightSearchPageResponse page = FlightResultPaging.page(FlightResultPaging.group(List.of(out, back, backToHindon)), r, NONE);

        assertThat(page.getFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E1");
        assertThat(page.getReturnFlights()).extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E2");
        assertThat(page.getOrigins()).containsExactly("DEL"); // the facets are the onward airports
        r.setOrigins(null);
        assertThat(FlightResultPaging.page(FlightResultPaging.group(List.of(out, back, backToHindon)), r, NONE).getReturnFlights())
                .extracting(FlightGroupResponse::getFlightNumber).containsExactly("6E2");
    }
}
