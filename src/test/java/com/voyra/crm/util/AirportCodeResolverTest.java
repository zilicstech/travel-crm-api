package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins AD-3 from the airport-picker plan: the legacy alias map must keep resolving names that
 * disagree with OurAirports' own spelling (its municipality for BLR is "Bengaluru", not
 * "Bangalore"), because TripjackFlightSearchProviderTest's fixture searches "Bangalore" and
 * would otherwise start failing as a silent side effect of this change.
 */
class AirportCodeResolverTest {

    @Test
    void resolve_threeLetterCodePassesThroughUnresolved() {
        assertThat(AirportCodeResolver.resolve("BLR")).isEqualTo("BLR");
    }

    @Test
    void resolve_legacyAliasesStillResolve() {
        assertThat(AirportCodeResolver.resolve("Bangalore")).isEqualTo("BLR");
        assertThat(AirportCodeResolver.resolve("Cochin")).isEqualTo("COK");
        assertThat(AirportCodeResolver.resolve("Trivandrum")).isEqualTo("TRV");
        assertThat(AirportCodeResolver.resolve("Maldives")).isEqualTo("MLE");
        assertThat(AirportCodeResolver.resolve("Switzerland")).isEqualTo("ZRH");
    }

    @Test
    void resolve_cityWithCountrySuffixStillResolvesViaTheAliasMap() {
        assertThat(AirportCodeResolver.resolve("Bangkok, Thailand")).isEqualTo("BKK");
    }

    @Test
    void resolve_datasetOnlyCityFallsThroughToAirportCache() {
        // Not in the legacy 113-entry map, but present in the bundled dataset.
        assertThat(AirportCodeResolver.resolve("Tiruchirappalli")).isEqualTo("TRZ");
    }

    @Test
    void resolve_unknownCityThrows() {
        assertThatThrownBy(() -> AirportCodeResolver.resolve("Atlantis"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown city");
    }

    @Test
    void resolve_blankThrows() {
        assertThatThrownBy(() -> AirportCodeResolver.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AirportCodeResolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValid_andNameOf() {
        assertThat(AirportCodeResolver.isValid("Bangalore")).isTrue();
        assertThat(AirportCodeResolver.isValid("Atlantis")).isFalse();
        assertThat(AirportCodeResolver.nameOf("BLR")).isEqualTo("Bengaluru (BLR)");
        assertThat(AirportCodeResolver.nameOf("ZZZ")).isNull();
    }
}
