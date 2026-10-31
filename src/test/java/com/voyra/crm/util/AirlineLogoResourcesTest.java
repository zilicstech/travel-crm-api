package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AirlineLogoResourcesTest {

    // the airlines MockFlightSearchProvider offers; MockFlightBookingProvider's "MK" is only its blank-code fallback
    @Test
    void everyMockAirlineHasALogo() {
        for (String code : List.of("6E", "AI", "SG", "UK", "EK")) {
            assertThat(getClass().getResource("/airlines/" + code + ".png")).as(code).isNotNull();
        }
    }
}
