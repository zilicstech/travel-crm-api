package com.voyra.crm.util;

import com.voyra.crm.enums.FlightCabin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FareCabinsTest {

    @Test
    void theSuppliersCabinWins() {
        assertThat(FareCabins.of("BUSINESS", "Eco Flex", FlightCabin.ECONOMY)).isEqualTo(FlightCabin.BUSINESS);
        assertThat(FareCabins.of(" premium economy ", null, null)).isEqualTo(FlightCabin.PREMIUM_ECONOMY);
    }

    @Test
    void elseTheFareNameSaysWhichCabin() {
        assertThat(FareCabins.of(null, "Premium Economy Flex", FlightCabin.ECONOMY)).isEqualTo(FlightCabin.PREMIUM_ECONOMY);
        assertThat(FareCabins.of("", "Business Semi Flex", FlightCabin.ECONOMY)).isEqualTo(FlightCabin.BUSINESS);
        assertThat(FareCabins.of("CABIN_X", "First Flex Plus", FlightCabin.ECONOMY)).isEqualTo(FlightCabin.FIRST);
        assertThat(FareCabins.of(null, "Ndc Economy Basic", FlightCabin.BUSINESS)).isEqualTo(FlightCabin.ECONOMY);
        assertThat(FareCabins.of(null, "Eco I Fullflex 2f", FlightCabin.BUSINESS)).isEqualTo(FlightCabin.ECONOMY);
    }

    @Test
    void elseTheSearchedCabinOrEconomy() {
        assertThat(FareCabins.of(null, "Low/Regular Fare", FlightCabin.BUSINESS)).isEqualTo(FlightCabin.BUSINESS);
        assertThat(FareCabins.of(null, null, null)).isEqualTo(FlightCabin.ECONOMY);
    }
}
