package com.voyra.crm.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The loaders behind the static initialiser, pointed at test CSVs so a missing, empty or broken file can be tried. */
class AirportCacheLoaderTest {

    @Test
    void aMissingResourceFailsByName() {
        assertThatThrownBy(() -> AirportCache.loadAirports("reference-test/missing.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Missing classpath resource reference-test/missing.csv");
        assertThatThrownBy(() -> AirportCache.loadCountries("reference-test/missing.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Missing classpath resource reference-test/missing.csv");
    }

    @Test
    void anEmptyFileFails() {
        assertThatThrownBy(() -> AirportCache.loadCountries("reference-test/empty.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessage("reference-test/empty.csv is empty");
    }

    @Test
    void aRowWithTheWrongNumberOfColumnsFails() {
        assertThatThrownBy(() -> AirportCache.loadCountries("reference-test/bad-row.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Malformed reference-data row (expected 2 columns, got 1): XX");
    }

    @Test
    void theHeaderAndBlankLinesAreSkippedAndQuotedCommasKept() {
        assertThat(AirportCache.loadCountries("reference-test/countries.csv"))
                .containsExactly(java.util.Map.entry("CD", "Congo, Democratic Republic of the"), java.util.Map.entry("IN", "India"));
    }

    @Test
    void theBiggerOfTwoSameCityAirportsWinsWhicheverComesFirst() {
        AirportCache.Airport small = new AirportCache.Airport("AAA", "Small Field", "Testville", "XX", 2);
        AirportCache.Airport big = new AirportCache.Airport("BBB", "Testville Intl", "Testville", "XX", 0);

        assertThat(AirportCache.findByPlaceName(List.of(small, big), "testville")).isEqualTo(big);
        assertThat(AirportCache.findByPlaceName(List.of(big, small), "testville")).isEqualTo(big);
    }
}
