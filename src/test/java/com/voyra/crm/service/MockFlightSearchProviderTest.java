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
}
