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

        FlightOfferResponse redEye = new MockFlightSearchProvider().search(query).stream()
                .filter(o -> o.getAirline().equals("Emirates")).findFirst().orElseThrow();
        FlightOfferResponse morning = new MockFlightSearchProvider().search(query).stream()
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

        FlightOfferResponse solo = new MockFlightSearchProvider().search(one).get(0);
        FlightOfferResponse group = new MockFlightSearchProvider().search(family).get(0);

        assertThat(group.getPrice()).isEqualByComparingTo(solo.getPrice());
        assertThat(solo.getTotalPrice()).isEqualByComparingTo(solo.getPrice());
        assertThat(group.getPassengers()).isEqualTo(3);
        // 2 adults + 1 child at 75% of the adult fare.
        assertThat(group.getTotalPrice()).isEqualByComparingTo(solo.getPrice().multiply(new java.math.BigDecimal("2.75")));
    }
}
