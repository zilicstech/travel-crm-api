package com.voyra.crm.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shipped CSVs, not just the parser - a botched refresh (truncated download, wrong
 * filter) must fail the build here rather than surface as a blank picker in a demo.
 */
class AirportCacheTest {

    @Test
    void all_parsesEveryRowWithAWellFormedCode() {
        List<AirportCache.Airport> airports = AirportCache.all();

        // Refresh-script guard mirrors scripts/refresh-reference-data.py's own 3500-5000 band.
        assertThat(airports.size()).isBetween(3500, 5000);

        for (AirportCache.Airport a : airports) {
            assertThat(a.iata()).hasSize(3).isEqualTo(a.iata().toUpperCase());
            assertThat(a.city()).isNotBlank();
            assertThat(a.countryCode()).hasSize(2);
            assertThat(a.sizeRank()).isBetween(0, 2);
        }
    }

    @Test
    void all_everyAirportCountryResolvesInTheCountryList() {
        var countries = AirportCache.countries();
        for (AirportCache.Airport a : AirportCache.all()) {
            assertThat(countries).as("country %s for airport %s", a.countryCode(), a.iata())
                    .containsKey(a.countryCode());
        }
    }

    @Test
    void byCode_knownIndianHub() {
        AirportCache.Airport blr = AirportCache.byCode("BLR");
        assertThat(blr).isNotNull();
        assertThat(blr.city()).isEqualTo("Bengaluru");
        assertThat(blr.countryCode()).isEqualTo("IN");
    }

    @Test
    void byCode_lowercaseAndUnknownCode() {
        assertThat(AirportCache.byCode("blr")).isNotNull();
        assertThat(AirportCache.byCode("ZZZ")).isNull();
        assertThat(AirportCache.byCode(null)).isNull();
    }

    @Test
    void findByPlaceName_exactCityPrefersTheLargerAirport() {
        AirportCache.Airport match = AirportCache.findByPlaceName("Bengaluru");
        assertThat(match).isNotNull();
        assertThat(match.iata()).isEqualTo("BLR");
    }

    @Test
    void findByPlaceName_noMatchReturnsNull() {
        assertThat(AirportCache.findByPlaceName("Atlantis")).isNull();
        assertThat(AirportCache.findByPlaceName("")).isNull();
        assertThat(AirportCache.findByPlaceName(null)).isNull();
    }
}
