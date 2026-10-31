package com.voyra.crm.cache;

import com.voyra.crm.util.ExcludeFromJacocoGenerated;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The airport/city/country reference data behind every location picker (flight route, hotel
 * city, visa country, transfer pickup/drop-off). A classpath CSV, not a database table - it is
 * the same fixed-reference-data pattern as {@link com.voyra.crm.util.GstStateCode}, just too
 * large to fit as literal {@code Map.put} calls. Refreshed by re-running
 * {@code scripts/refresh-reference-data.py} and committing the two CSVs under
 * {@code src/main/resources/reference/}; there is deliberately no migration and no admin UI to
 * edit it, because it does not vary per tenant.
 *
 * <p>Loaded lazily on first use rather than via {@code config/CacheLoader} on
 * {@code ApplicationReadyEvent}, so {@link com.voyra.crm.util.AirportCodeResolver} stays a plain
 * static utility a unit test can call with no Spring context.
 */
@Slf4j
public final class AirportCache {

    private static final String AIRPORTS_RESOURCE = "reference/airports.csv";
    private static final String COUNTRIES_RESOURCE = "reference/countries.csv";

    private static final List<Airport> AIRPORTS;
    private static final Map<String, Airport> BY_CODE = new ConcurrentHashMap<>();
    private static final Map<String, String> COUNTRIES;

    static {
        AIRPORTS = loadAirports(AIRPORTS_RESOURCE);
        AIRPORTS.forEach(a -> BY_CODE.put(a.iata(), a));
        COUNTRIES = loadCountries(COUNTRIES_RESOURCE);
        log.info("Reference data loaded: {} airports, {} countries", AIRPORTS.size(), COUNTRIES.size());
    }

    private AirportCache() {
    }

    /** Every airport, in the CSV's committed order (size_rank then IATA - see the refresh script). */
    public static List<Airport> all() {
        return AIRPORTS;
    }

    public static Airport byCode(String iataCode) {
        if (iataCode == null) {
            return null;
        }
        return BY_CODE.get(iataCode.trim().toUpperCase());
    }

    /** Country name keyed by ISO alpha-2 code, in the source file's alphabetical-by-name order. */
    public static Map<String, String> countries() {
        return COUNTRIES;
    }

    /**
     * Best-effort match of a free-text place name (city or airport name) against the dataset -
     * used by {@link com.voyra.crm.util.AirportCodeResolver} once its legacy alias map misses.
     * Exact city-name match wins; falls back to the first airport whose name or city starts
     * with the query.
     */
    public static Airport findByPlaceName(String query) {
        return findByPlaceName(AIRPORTS, query);
    }

    static Airport findByPlaceName(List<Airport> airports, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim().toUpperCase();
        Airport exactCity = null;
        Airport prefixMatch = null;
        for (Airport a : airports) {
            if (a.city().equalsIgnoreCase(q)) {
                if (exactCity == null || a.sizeRank() < exactCity.sizeRank()) {
                    exactCity = a;
                }
            } else if (prefixMatch == null
                    && (a.city().toUpperCase().startsWith(q) || a.name().toUpperCase().startsWith(q))) {
                prefixMatch = a;
            }
        }
        return exactCity != null ? exactCity : prefixMatch;
    }

    static List<Airport> loadAirports(String resource) {
        // iata,name,city,country_code,size_rank
        return rows(resource, 5).stream()
                .map(cols -> new Airport(cols[0], cols[1], cols[2], cols[3], Integer.parseInt(cols[4]))).toList();
    }

    static Map<String, String> loadCountries(String resource) {
        Map<String, String> countries = new LinkedHashMap<>(); // code,name
        rows(resource, 2).forEach(cols -> countries.put(cols[0], cols[1]));
        return countries;
    }

    /** A classpath CSV's rows after its header line, blank lines skipped, each split into {@code expectedCols} fields. */
    private static List<String[]> rows(String resource, int expectedCols) {
        InputStream in = AirportCache.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + resource);
        }
        List<String> lines = readLines(in, resource);
        if (lines.isEmpty()) {
            throw new IllegalStateException(resource + " is empty");
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                rows.add(splitCsvLine(line, expectedCols));
            }
        }
        return rows;
    }

    /** Reads and closes an opened classpath resource; the IOException the compiler forces cannot be provoked by a test. */
    @ExcludeFromJacocoGenerated
    private static List<String> readLines(InputStream in, String resource) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resource, e);
        }
    }

    /**
     * A hand-rolled splitter rather than a CSV library dependency: the source data quotes a
     * field only when it contains a comma (airport/country names occasionally do, e.g.
     * "Congo, Democratic Republic of the"), so a simple quote-aware split covers it without
     * pulling in Commons CSV / OpenCSV for two files.
     */
    private static String[] splitCsvLine(String line, int expectedCols) {
        List<String> out = new ArrayList<>(expectedCols);
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        if (out.size() != expectedCols) {
            throw new IllegalStateException("Malformed reference-data row (expected " + expectedCols
                    + " columns, got " + out.size() + "): " + line);
        }
        return out.toArray(new String[0]);
    }

    public record Airport(String iata, String name, String city, String countryCode, int sizeRank) {
    }
}
