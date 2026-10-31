package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FlightCabin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockFlightSearchProviderCoverageTest {

    private static FlightSearchQuery query(FlightCabin cabin) {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin("DEL");
        q.setDestination("BOM");
        q.setAdults(1);
        q.setCabin(cabin);
        return q;
    }

    @Test
    void aSearchWithoutCabinOrDateIsEconomyWithNoDates() {
        List<FlightOfferResponse> offers = new MockFlightSearchProvider().search(query(null));

        assertThat(offers).isNotEmpty().allSatisfy(o -> {
            assertThat(o.getOfferId()).endsWith("-ECONOMY");
            assertThat(o.getDepartDate()).isNull();
            assertThat(o.getArriveDate()).isNull();
        });
    }

    @Test
    void premiumCabinsCostTheirMultipleOfEconomy() {
        String economy = new MockFlightSearchProvider().search(query(FlightCabin.ECONOMY)).get(0).getOfferId();
        BigDecimal base = MockFlightSearchProvider.adultFare(economy);
        String stem = economy.substring(0, economy.lastIndexOf('-') + 1);

        assertThat(MockFlightSearchProvider.adultFare(stem + "BUSINESS")).isEqualByComparingTo(base.multiply(new BigDecimal("3.2")).setScale(2));
        assertThat(MockFlightSearchProvider.adultFare(stem + "PREMIUM_ECONOMY")).isEqualByComparingTo(base.multiply(new BigDecimal("1.6")).setScale(2));
        assertThat(MockFlightSearchProvider.adultFare(stem + "FIRST")).isEqualByComparingTo(base.multiply(new BigDecimal("5.0")).setScale(2));
        assertThat(MockFlightSearchProvider.adultFare(economy + MockFlightSearchProvider.RETURN_SUFFIX)).isEqualByComparingTo(base);
    }

    @Test
    void anIdThatIsNotAMockOfferHasNoFare() {
        String id = new MockFlightSearchProvider().search(query(FlightCabin.ECONOMY)).get(0).getOfferId();
        String[] p = id.split("-");   // mock, airline, HH:mm, tier, cabin

        assertThat(MockFlightSearchProvider.adultFare(null)).isNull();
        assertThat(MockFlightSearchProvider.adultFare("tj-" + p[1] + "-" + p[2] + "-" + p[3] + "-" + p[4])).isNull();
        assertThat(MockFlightSearchProvider.adultFare(id + "-X")).isNull();
        assertThat(MockFlightSearchProvider.adultFare("mock-" + p[1] + "-" + p[2])).isNull();
        assertThat(MockFlightSearchProvider.adultFare("mock-nosuchair-" + p[2] + "-" + p[3] + "-" + p[4])).isNull();
        assertThat(MockFlightSearchProvider.adultFare("mock-" + p[1] + "-23:59-" + p[3] + "-" + p[4])).isNull();
        assertThat(MockFlightSearchProvider.adultFare("mock-" + p[1] + "-" + p[2] + "-NOTIER-" + p[4])).isNull();
        assertThat(MockFlightSearchProvider.adultFare("mock-" + p[1] + "-" + p[2] + "-" + p[3] + "-NOCABIN")).isNull();
    }
}
