package com.voyra.crm.cache;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final Map<String, String> COUNTRIES = new LinkedHashMap<>();

    static {
        AIRPORTS = loadAirports();
        AIRPORTS.forEach(a -> BY_CODE.put(a.iata(), a));
        loadCountries();
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
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim().toUpperCase();
        Airport exactCity = null;
        Airport prefixMatch = null;
        for (Airport a : AIRPORTS) {
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

    private static List<Airport> loadAirports() {
        List<Airport> out = new ArrayList<>();
        try (InputStream in = AirportCache.class.getClassLoader().getResourceAsStream(AIRPORTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + AIRPORTS_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = reader.readLine(); // iata,name,city,country_code,size_rank
                if (header == null) {
                    throw new IllegalStateException(AIRPORTS_RESOURCE + " is empty");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] cols = splitCsvLine(line, 5);
                    out.add(new Airport(cols[0], cols[1], cols[2], cols[3], Integer.parseInt(cols[4])));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + AIRPORTS_RESOURCE, e);
        }
        return Collections.unmodifiableList(out);
    }

    private static void loadCountries() {
        try (InputStream in = AirportCache.class.getClassLoader().getResourceAsStream(COUNTRIES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + COUNTRIES_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = reader.readLine(); // code,name
                if (header == null) {
                    throw new IllegalStateException(COUNTRIES_RESOURCE + " is empty");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] cols = splitCsvLine(line, 2);
                    COUNTRIES.put(cols[0], cols[1]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + COUNTRIES_RESOURCE, e);
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
