package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.HotelSearchProvider;
import com.voyra.crm.service.SupplierCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real Tripjack hotel search (Hotel API v3), behind {@code app.hotel-supplier.provider=tripjack}.
 *
 * <p>v3 searches by Tripjack hotel ids, not by city, and lives on its own host (not the flight
 * one): a city is turned into region ids through the paged city list, the regions into hotel ids
 * through the hotel mapping, and only then is {@code /hms/v3/hotel/listing} called, at most 100
 * ids per call. Star rating and address come from the hotel content call, best effort. The
 * contract is taken from a public live v3 integration - see the plan
 * {@code docs/superpowers/plans/2026-10-18-tripjack-hotel-search.md}.
 *
 * <p>Everything cached here is Tripjack's static data (cities, hotel ids, nationalities, hotel
 * content), the same for every agency, keyed by host - never a price and never a credential.
 */
@Service
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "tripjack")
@Slf4j
public class TripjackHotelSearchProvider implements HotelSearchProvider {

    static final int LISTING_BATCH = 100;
    static final int MAX_BATCHES = 3;
    static final int MAX_OFFERS = 60;
    static final int DEFAULT_CHILD_AGE = 8;
    private static final Duration CITY_INDEX_TTL = Duration.ofDays(7);
    private static final Duration HOTEL_IDS_TTL = Duration.ofDays(1);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Common renames - Tripjack lists some cities under their older or newer name. */
    private static final Map<String, List<String>> CITY_ALIASES = Map.ofEntries(
            Map.entry("DELHI", List.of("NEW DELHI", "DELHI")), Map.entry("NEW DELHI", List.of("NEW DELHI", "DELHI")),
            Map.entry("BANGALORE", List.of("BENGALURU", "BANGALORE")), Map.entry("BENGALURU", List.of("BENGALURU", "BANGALORE")),
            Map.entry("BOMBAY", List.of("MUMBAI", "BOMBAY")), Map.entry("MUMBAI", List.of("MUMBAI", "BOMBAY")),
            Map.entry("MADRAS", List.of("CHENNAI", "MADRAS")), Map.entry("CHENNAI", List.of("CHENNAI", "MADRAS")),
            Map.entry("CALCUTTA", List.of("KOLKATA", "CALCUTTA")), Map.entry("KOLKATA", List.of("KOLKATA", "CALCUTTA")),
            Map.entry("COCHIN", List.of("KOCHI", "COCHIN")), Map.entry("KOCHI", List.of("KOCHI", "COCHIN")),
            Map.entry("GURGAON", List.of("GURUGRAM", "GURGAON")), Map.entry("GURUGRAM", List.of("GURUGRAM", "GURGAON")),
            Map.entry("TRIVANDRUM", List.of("THIRUVANANTHAPURAM", "TRIVANDRUM")),
            Map.entry("GOA", List.of("GOA", "NORTH GOA", "SOUTH GOA", "PANAJI", "CALANGUTE", "CANDOLIM", "BAGA")));

    private record Region(String id, String countryName) { }
    private record CityIndex(Map<String, List<Region>> cities, Instant builtAt) { }
    private record Cached<T>(T value, Instant at) { }

    private final SupplierCredentialService supplierCredentialService;
    private final RestClient.Builder restClientBuilder;
    private final String uatBaseUrl;
    private final String prodBaseUrl;

    private final Map<String, CityIndex> cityIndexes = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<String>>> hotelIds = new ConcurrentHashMap<>();
    private final Map<String, Cached<Map<String, String>>> nationalities = new ConcurrentHashMap<>();
    private final Map<String, Cached<JsonNode>> content = new ConcurrentHashMap<>();

    public TripjackHotelSearchProvider(SupplierCredentialService supplierCredentialService,
                                       @Qualifier("tripjackRestClientBuilder") RestClient.Builder restClientBuilder,
                                       @Value("${app.hotel-supplier.tripjack.uat-base-url:https://apitest-hms.tripjack.com}") String uatBaseUrl,
                                       @Value("${app.hotel-supplier.tripjack.prod-base-url:https://hms-search.tripjack.com}") String prodBaseUrl) {
        this.supplierCredentialService = supplierCredentialService;
        this.restClientBuilder = restClientBuilder;
        this.uatBaseUrl = uatBaseUrl;
        this.prodBaseUrl = prodBaseUrl;
    }

    @Override
    public List<HotelOfferResponse> search(HotelSearchQuery query) {
        log.info("Tripjack hotel search start: {} ({}) {} -> {}", query.getCity(), query.getCountryCode(), query.getCheckIn(), query.getCheckOut());
        SupplierCredentialRevealResponse credential = supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        String host = credential.getEnvironment() == SupplierEnvironment.PROD ? prodBaseUrl : uatBaseUrl;
        RestClient client = restClientBuilder.clone().baseUrl(host).defaultHeader("apikey", credential.getApiKey()).build();
        long startedAt = System.currentTimeMillis();

        List<String> regions = regionIds(client, host, query.getCity(), query.getCountryCode());
        List<String> hids = hotelIdsFor(client, host, regions);
        if (hids.isEmpty()) {
            return List.of();
        }
        String nationality = nationalityId(client, host, "IN");
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        List<Map<String, Object>> rooms = rooms(query);

        List<JsonNode> listed = new ArrayList<>();
        SupplierException firstError = null;
        for (int i = 0; i < hids.size() && i < LISTING_BATCH * MAX_BATCHES; i += LISTING_BATCH) {
            List<Long> batch = hids.subList(i, Math.min(i + LISTING_BATCH, hids.size())).stream().map(Long::valueOf).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("checkIn", query.getCheckIn().toString());
            body.put("checkOut", query.getCheckOut().toString());
            body.put("rooms", rooms);
            body.put("currency", "INR");
            body.put("correlationId", correlationId);
            body.put("nationality", nationality);
            body.put("hids", batch);
            try {
                call(client, "POST", "/hms/v3/hotel/listing", body).path("hotels").forEach(listed::add);
            } catch (SupplierException e) {
                // One failed batch must not lose the others' results.
                if (firstError == null) firstError = e;
            }
        }
        if (listed.isEmpty() && firstError != null) {
            throw firstError;
        }

        List<JsonNode> withOptions = listed.stream().filter(h -> h.path("options").size() > 0).toList();
        Map<String, JsonNode> info = contentFor(client, host, withOptions.stream().map(h -> h.path("tjHotelId").asText()).toList());
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
        List<HotelOfferResponse> offers = withOptions.stream()
                .map(h -> toOffer(h, info.get(h.path("tjHotelId").asText()), query.getCity(), nights))
                .sorted(Comparator.comparing(HotelOfferResponse::getPrice))
                .limit(MAX_OFFERS)
                .toList();
        log.info("Tripjack hotel search ok: {} hotels from {} ids in {} ms", offers.size(), hids.size(), System.currentTimeMillis() - startedAt);
        return offers;
    }

    /** Adults and children spread as evenly as possible over the rooms - Tripjack prices per room. */
    static List<Map<String, Object>> rooms(HotelSearchQuery query) {
        int roomCount = Math.max(1, query.getRooms());
        int adults = Math.max(roomCount, query.getAdults());
        int children = Math.max(0, query.getChildren());
        List<Integer> ages = query.getChildAges() == null ? List.of() : query.getChildAges();
        List<Map<String, Object>> rooms = new ArrayList<>();
        int childIndex = 0;
        for (int r = 0; r < roomCount; r++) {
            int roomAdults = adults / roomCount + (r < adults % roomCount ? 1 : 0);
            int roomChildren = children / roomCount + (r < children % roomCount ? 1 : 0);
            Map<String, Object> room = new LinkedHashMap<>();
            room.put("adults", roomAdults);
            if (roomChildren > 0) {
                List<Integer> roomAges = new ArrayList<>();
                for (int c = 0; c < roomChildren; c++, childIndex++) {
                    roomAges.add(childIndex < ages.size() && ages.get(childIndex) != null ? ages.get(childIndex) : DEFAULT_CHILD_AGE);
                }
                room.put("children", roomChildren);
                room.put("childAge", roomAges);
            }
            rooms.add(room);
        }
        return rooms;
    }

    private HotelOfferResponse toOffer(JsonNode hotel, JsonNode info, String city, int nights) {
        JsonNode best = null;
        for (JsonNode o : hotel.path("options")) {
            if (best == null || o.path("pricing").path("totalPrice").asDouble() < best.path("pricing").path("totalPrice").asDouble()) {
                best = o;
            }
        }
        Set<String> roomNames = new LinkedHashSet<>();
        best.path("roomInfo").forEach(r -> roomNames.add(r.path("name").asText()));
        JsonNode address = info == null ? null : info.path("locale").path("address");
        String fullAddress = address == null ? "" : address.path("fulladdr").asText(
                String.join(", ", List.of(address.path("line_1").asText(""), address.path("city").asText("")).stream().filter(s -> !s.isBlank()).toList()));
        boolean refundable = best.path("cancellation").path("isRefundable").asBoolean(false);
        return HotelOfferResponse.builder()
                .offerId(best.path("optionId").asText())
                .hotelName(info != null && !info.path("name").asText("").isBlank() ? info.path("name").asText() : hotel.path("name").asText())
                .starRating(info == null ? 0 : (int) Math.round(info.path("star_rating").asDouble(0)))
                .city(city)
                .address(fullAddress)
                .roomType(String.join(" / ", roomNames))
                .boardBasis(best.path("mealBasis").asText(""))
                .nights(nights)
                .supplier("Tripjack")
                .price(new BigDecimal(best.path("pricing").path("totalPrice").asText("0")).setScale(2, java.math.RoundingMode.HALF_UP))
                .currency(best.path("pricing").path("currency").asText("INR"))
                .refundable(refundable)
                .cancellationPolicy(cancellationPolicy(best.path("cancellation"), refundable))
                .build();
    }

    private static String cancellationPolicy(JsonNode cancellation, boolean refundable) {
        if (!refundable) return "Non-refundable";
        String deadline = cancellation.path("deadlineDateTime").asText("");
        return deadline.isBlank() ? "Refundable" : "Free cancellation until " + deadline.replace('T', ' ');
    }

    // ── City → region ids → hotel ids ───────────────────────────────────────

    private List<String> regionIds(RestClient client, String host, String city, String countryCode) {
        CityIndex index = cityIndex(client, host);
        String key = city.trim().toUpperCase(Locale.ROOT);
        List<Region> matches = CITY_ALIASES.getOrDefault(key, List.of(key)).stream()
                .flatMap(name -> index.cities().getOrDefault(name, List.of()).stream()).toList();
        if (countryCode != null && !countryCode.isBlank()) {
            String country = new Locale("", countryCode.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH).toUpperCase(Locale.ROOT);
            List<Region> inCountry = matches.stream().filter(r -> r.countryName().equals(country)).toList();
            if (!inCountry.isEmpty()) matches = inCountry;
        }
        if (matches.isEmpty()) {
            throw new SupplierException("Tripjack has no hotels listed for \"" + city + "\" - try the main city name", null);
        }
        return matches.stream().map(Region::id).distinct().toList();
    }

    /** The whole paged city list, built once per host and rebuilt weekly - the slow part of a cold start. */
    private CityIndex cityIndex(RestClient client, String host) {
        CityIndex current = cityIndexes.get(host);
        if (current != null && current.builtAt().plus(CITY_INDEX_TTL).isAfter(Instant.now())) {
            return current;
        }
        synchronized (cityIndexes) {
            current = cityIndexes.get(host);
            if (current != null && current.builtAt().plus(CITY_INDEX_TTL).isAfter(Instant.now())) {
                return current;
            }
            Map<String, List<Region>> cities = new HashMap<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode page = call(client, "GET", "/hms/v3/content/fetch-city-regionIds?limit=2000" + (cursor == null ? "" : "&cursor=" + cursor), null);
                for (JsonNode r : page.path("hotelCityRegionIds")) {
                    String name = r.path("cityName").asText("").trim().toUpperCase(Locale.ROOT);
                    if (!name.isEmpty()) {
                        cities.computeIfAbsent(name, k -> new ArrayList<>())
                                .add(new Region(r.path("cityRegionId").asText(), r.path("countryName").asText("").toUpperCase(Locale.ROOT)));
                    }
                }
                cursor = page.path("hasMore").asBoolean(false) ? page.path("nextCursor").asText(null) : null;
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < 500);
            log.info("Tripjack hotel city index built: {} cities from {} pages ({})", cities.size(), pages, host);
            CityIndex built = new CityIndex(cities, Instant.now());
            cityIndexes.put(host, built);
            return built;
        }
    }

    private List<String> hotelIdsFor(RestClient client, String host, List<String> regionIds) {
        String key = host + "|" + String.join(",", regionIds.stream().sorted().toList());
        Cached<List<String>> cached = hotelIds.get(key);
        if (cached != null && cached.at().plus(HOTEL_IDS_TTL).isAfter(Instant.now())) {
            return cached.value();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("regionIds", regionIds);
        body.put("page", 0);
        body.put("size", 2000);
        List<String> ids = new ArrayList<>();
        call(client, "POST", "/hms/v3/content/fetch-hotel-mapping", body).path("hotels")
                .forEach(h -> { String id = h.path("tjHotelId").asText(""); if (!id.isBlank()) ids.add(id); });
        hotelIds.put(key, new Cached<>(ids, Instant.now()));
        return ids;
    }

    private String nationalityId(RestClient client, String host, String iso2) {
        Cached<Map<String, String>> cached = nationalities.get(host);
        if (cached == null || cached.at().plus(CITY_INDEX_TTL).isBefore(Instant.now())) {
            Map<String, String> map = new HashMap<>();
            call(client, "GET", "/hms/v3/nationality-info", null).path("nationalityInfos")
                    .forEach(n -> map.put(n.path("code").asText("").toUpperCase(Locale.ROOT), n.path("countryId").asText()));
            cached = new Cached<>(map, Instant.now());
            nationalities.put(host, cached);
        }
        String id = cached.value().get(iso2);
        if (id == null) {
            throw new SupplierException("Tripjack hotels don't support nationality " + iso2, null);
        }
        return id;
    }

    /** Star rating and address; a failure only costs the listing its stars, never the search. */
    private Map<String, JsonNode> contentFor(RestClient client, String host, List<String> hids) {
        Map<String, JsonNode> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String hid : hids) {
            Cached<JsonNode> c = content.get(host + "|" + hid);
            if (c != null && c.at().plus(CITY_INDEX_TTL).isAfter(Instant.now())) out.put(hid, c.value()); else missing.add(hid);
        }
        for (int i = 0; i < missing.size(); i += LISTING_BATCH) {
            try {
                JsonNode res = call(client, "POST", "/hms/v3/content/fetch-hotel-content",
                        Map.of("hotelIds", missing.subList(i, Math.min(i + LISTING_BATCH, missing.size()))));
                for (JsonNode h : res.path("hotels")) {
                    String hid = h.path("tjHotelId").asText("");
                    if (hid.isBlank()) continue;
                    out.put(hid, h);
                    content.put(host + "|" + hid, new Cached<>(h, Instant.now()));
                }
            } catch (SupplierException e) {
                log.warn("Tripjack hotel content failed, listing without stars/address: {}", e.getMessage());
            }
        }
        return out;
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    /** Read as a raw string for the same reason as the flight provider: Tripjack's Content-Type can disagree with its JSON body. */
    private JsonNode call(RestClient client, String method, String path, Object body) {
        String endpoint = path.split("\\?")[0];
        String raw;
        try {
            raw = "GET".equals(method)
                    ? client.get().uri(path).retrieve().body(String.class)
                    : client.post().uri(path).body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            log.error("Tripjack hotel {} HTTP {}: {}", endpoint, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new SupplierException("Tripjack hotel search failed (" + endpoint + ", HTTP " + e.getStatusCode().value() + ")"
                    + messageOf(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            log.error("Tripjack hotel {} unreachable: {}", endpoint, e.getMessage());
            throw new SupplierException("Tripjack hotel search could not reach Tripjack", e);
        }
        JsonNode json;
        try {
            json = raw == null || raw.isBlank() ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SupplierException("Tripjack hotel " + endpoint + " returned an unparseable response", e);
        }
        boolean failed = json.path("status").has("success") && !json.path("status").path("success").asBoolean()
                || json.path("error").isObject() || json.path("errors").size() > 0;
        if (failed) {
            log.error("Tripjack hotel {} did not succeed: {}", endpoint, raw);
            throw new SupplierException("Tripjack hotel search returned an error" + messageOf(raw), null);
        }
        return json;
    }

    private static String messageOf(String raw) {
        try {
            JsonNode j = OBJECT_MAPPER.readTree(raw == null ? "" : raw);
            String m = j.path("error").path("message").asText(j.path("errors").path(0).path("message").asText(""));
            return m.isBlank() ? "" : ": " + m;
        } catch (Exception e) {
            return "";
        }
    }
}
