package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MockFlightSearchProviderTest {

    @Test
    void anOvernightFlightArrivesTheNextDay() {
        FlightSearchQuery query = new FlightSearchQuery();
        query.setOrigin("DEL");
        query.setDestination("DXB");
        query.setDepartDate(LocalDate.of(2026, 11, 20));
        query.setAdults(1);

        FlightOfferResponse redEye = new MockFlightSearchProvider().search(query, null).stream()
                .filter(o -> o.getAirline().equals("Emirates")).findFirst().orElseThrow();
        FlightOfferResponse morning = new MockFlightSearchProvider().search(query, null).stream()
                .filter(o -> o.getAirline().equals("IndiGo")).findFirst().orElseThrow();

        assertThat(redEye.getDepartDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(redEye.getArriveDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(morning.getArriveDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void priceIsPerAdultAndTotalCoversThePartyLikeTheRealSupplier() {
        FlightSearchQuery one = new FlightSearchQuery();
        one.setOrigin("DEL");
        one.setDestination("DXB");
        one.setDepartDate(LocalDate.of(2026, 12, 15));
        FlightSearchQuery family = new FlightSearchQuery();
        family.setOrigin("DEL");
        family.setDestination("DXB");
        family.setDepartDate(LocalDate.of(2026, 12, 15));
        family.setAdults(2);
        family.setChildren(1);

        FlightOfferResponse solo = new MockFlightSearchProvider().search(one, null).get(0);
        FlightOfferResponse group = new MockFlightSearchProvider().search(family, null).get(0);

        assertThat(group.getPrice()).isEqualByComparingTo(solo.getPrice());
        assertThat(solo.getTotalPrice()).isEqualByComparingTo(solo.getPrice());
        assertThat(group.getPassengers()).isEqualTo(3);
        assertThat(group.getBaseFare()).isEqualByComparingTo(group.getTotalPrice().multiply(new java.math.BigDecimal("0.85")));
        // 2 adults + 1 child at 75% of the adult fare.
        assertThat(group.getTotalPrice()).isEqualByComparingTo(solo.getPrice().multiply(new java.math.BigDecimal("2.75")));
    }

    @Test
    void aRoundTripAddsReturnOffersTheWayBackPricedFromTheirOwnId() {
        FlightSearchQuery query = new FlightSearchQuery();
        query.setOrigin("DEL");
        query.setDestination("BOM");
        query.setDepartDate(LocalDate.of(2026, 11, 26));
        query.setReturnDate(LocalDate.of(2026, 11, 29));

        var offers = new MockFlightSearchProvider().search(query, null);
        var onward = offers.stream().filter(o -> o.getDirection() == com.voyra.crm.enums.FlightDirection.ONWARD).toList();
        var back = offers.stream().filter(o -> o.getDirection() == com.voyra.crm.enums.FlightDirection.RETURN).toList();

        assertThat(onward).hasSize(back.size()).isNotEmpty();
        assertThat(offers).noneMatch(o -> o.getDirection() == com.voyra.crm.enums.FlightDirection.COMBO);
        FlightOfferResponse r = back.get(0);
        assertThat(r.getOfferId()).endsWith("-R");
        assertThat(r.getOrigin()).isEqualTo("BOM");
        assertThat(r.getDestination()).isEqualTo("DEL");
        assertThat(r.getDepartDate()).isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(r.getLegs()).singleElement().satisfies(l -> assertThat(l.getOrigin()).isEqualTo("BOM"));
        assertThat(MockFlightSearchProvider.adultFare(r.getOfferId())).isEqualByComparingTo(r.getPrice());
        assertThat(MockFlightSearchProvider.adultFare("mock-indigo-06:15-PUBLISHED-ECONOMY-X")).isNull();
    }

    @Test
    void aStudentOrSeniorCitizenSearchReturnsTheSameFlightsWithSpecialFaresUnderThePublishedOne() {
        FlightSearchQuery query = new FlightSearchQuery();
        query.setOrigin("DEL");
        query.setDestination("BOM");
        query.setDepartDate(LocalDate.of(2026, 11, 26));
        var regular = new MockFlightSearchProvider().search(query, null);
        java.math.BigDecimal published = regular.get(0).getPrice(); // IndiGo 06:15 PUBLISHED

        for (var category : java.util.List.of(com.voyra.crm.enums.FareCategory.STUDENT, com.voyra.crm.enums.FareCategory.SENIOR_CITIZEN)) {
            query.setFareCategory(category);
            var special = new MockFlightSearchProvider().search(query, null);

            assertThat(special).hasSize(10).allMatch(o -> o.getFareType().equals(category.name())); // two fares on each of the five flights
            assertThat(special).extracting(FlightOfferResponse::getFlightNumber).containsOnlyElementsOf(
                    regular.stream().map(FlightOfferResponse::getFlightNumber).toList());
            FlightOfferResponse first = special.get(0);
            assertThat(first.getOfferId()).isEqualTo("mock-indigo-06:15-" + category.name() + "-ECONOMY");
            assertThat(first.getPrice()).isLessThan(published).isEqualByComparingTo(published.multiply(new java.math.BigDecimal("0.92")));
            assertThat(MockFlightSearchProvider.adultFare(first.getOfferId())).isEqualByComparingTo(first.getPrice());
            assertThat(special).allMatch(o -> MockFlightSearchProvider.needsDocumentId(o.getOfferId()));
        }
        assertThat(regular).noneMatch(o -> MockFlightSearchProvider.needsDocumentId(o.getOfferId()))
                .noneMatch(o -> o.getFareType().equals("STUDENT"));
        assertThat(MockFlightSearchProvider.needsDocumentId("o")).isFalse();
        assertThat(MockFlightSearchProvider.needsDocumentId("mock-indigo-06:15-STUDENT_FLEXI-ECONOMY-R")).isTrue();
    }
}
