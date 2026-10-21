package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelDetailResponse;
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
    private final Map<String, Cached<JsonNode>> statics = new ConcurrentHashMap<>();
    private static final Duration STATIC_DETAIL_TTL = Duration.ofHours(24);
    /** Listing and content batches go out side by side, not one after another - most of a search's wait. */
    private static final java.util.concurrent.ExecutorService CALLS = java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "tripjack-hotel");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean shapeLogged = new java.util.concurrent.atomic.AtomicBoolean();

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
        Conn conn = connect();
        RestClient client = conn.client();
        String host = conn.host();
        long startedAt = System.currentTimeMillis();

        List<String> regions = regionIds(client, host, query.getCity(), query.getCountryCode());
        List<String> hids = hotelIdsFor(client, host, regions);
        if (hids.isEmpty()) {
            return List.of();
        }
        String nationality = nationalityId(client, host, "IN");
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        List<Map<String, Object>> rooms = rooms(query);

        List<java.util.concurrent.CompletableFuture<JsonNode>> batches = new ArrayList<>();
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
            batches.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> call(client, "POST", "/hms/v3/hotel/listing", body), CALLS));
        }
        List<JsonNode> listed = new ArrayList<>();
        SupplierException firstError = null;
        for (var batch : batches) {
            try {
                batch.join().path("hotels").forEach(listed::add);
            } catch (java.util.concurrent.CompletionException e) {
                // One failed batch must not lose the others' results.
                if (firstError == null) firstError = e.getCause() instanceof SupplierException se ? se : new SupplierException("Tripjack hotel search failed", e.getCause());
            }
        }
        if (listed.isEmpty() && firstError != null) {
            throw firstError;
        }
        if (!listed.isEmpty() && shapeLogged.compareAndSet(false, true)) {
            List<String> fields = new ArrayList<>();
            listed.get(0).fieldNames().forEachRemaining(fields::add);
            log.info("Tripjack hotel listing fields: {}", fields);
        }

        List<JsonNode> withOptions = listed.stream().filter(h -> h.path("options").size() > 0).toList();
        Map<String, JsonNode> info = contentFor(client, host, withOptions.stream().map(TripjackHotelSearchProvider::hotelIdOf).filter(id -> !id.isBlank()).toList());
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
        List<HotelOfferResponse> offers = withOptions.stream()
                .map(h -> toOffer(h, info.get(hotelIdOf(h)), query.getCity(), nights, correlationId))
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

    private record Conn(RestClient client, String host) { }

    private Conn connect() {
        SupplierCredentialRevealResponse credential = supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        String host = credential.getEnvironment() == SupplierEnvironment.PROD ? prodBaseUrl : uatBaseUrl;
        return new Conn(restClientBuilder.clone().baseUrl(host).defaultHeader("apikey", credential.getApiKey()).build(), host);
    }

    private HotelOfferResponse toOffer(JsonNode hotel, JsonNode info, String city, int nights, String correlationId) {
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
                .hotelId(hotelIdOf(hotel).isBlank() ? null : hotelIdOf(hotel))
                .imageUrl(info == null ? null : images(info.path("images")).stream().findFirst().map(HotelDetailResponse.Image::getThumbUrl).orElse(null))
                .correlationId(correlationId)
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

    static String cancellationPolicy(JsonNode cancellation, boolean refundable) {
        if (!refundable) return "Non-refundable";
        String deadline = cancellation.path("deadlineDateTime").asText("");
        // The last free slab ends where the penalty starts.
        for (JsonNode p : cancellation.path("penalties")) {
            if (p.path("amount").asDouble(-1) == 0 && !p.path("to").asText("").isBlank()) deadline = p.path("to").asText();
        }
        return deadline.isBlank() ? "Refundable" : "Free cancellation until " + deadline.replace('T', ' ').replaceAll("(\\d\\d:\\d\\d):\\d\\d$", "$1");
    }

    // ── One hotel's page ────────────────────────────────────────────────────

    /**
     * Every bookable option from the pricing API (tied to the search by its correlationId) and the hotel's static
     * detail - every photo, descriptions, amenities, policies and each room type's own photos and facts, joined to an
     * option's rooms by room id. Static detail is cached for a day, as Tripjack asks; without it the page still lists
     * the options, with the photos the search's content call gave.
     */
    @Override
    public HotelDetailResponse detail(String hotelId, HotelSearchQuery query) {
        Conn conn = connect();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correlationId", query.getCorrelationId() != null && !query.getCorrelationId().isBlank()
                ? query.getCorrelationId() : UUID.randomUUID().toString().replace("-", ""));
        body.put("hid", hotelId);
        body.put("checkIn", query.getCheckIn().toString());
        body.put("checkOut", query.getCheckOut().toString());
        body.put("rooms", rooms(query));
        body.put("currency", "INR");
        body.put("nationality", nationalityId(conn.client(), conn.host(), "IN"));
        JsonNode pricing = call(conn.client(), "POST", "/hms/v3/hotel/pricing", body);
        JsonNode stat = staticDetail(conn.client(), conn.host(), hotelId);
        JsonNode info = stat != null ? stat : contentFor(conn.client(), conn.host(), List.of(hotelId)).get(hotelId);
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
        return toDetail(hotelId, pricing, info, query.getCity(), nights);
    }

    static HotelDetailResponse toDetail(String hotelId, JsonNode pricing, JsonNode info, String city, int nights) {
        JsonNode none = OBJECT_MAPPER.createObjectNode();
        JsonNode i = info != null ? info : none;
        Map<String, JsonNode> roomTypes = new HashMap<>();
        i.path("rooms").forEach(r -> roomTypes.put(r.path("id").asText(), r));

        List<HotelDetailResponse.Image> images = new ArrayList<>(images(i.path("images")));
        Set<String> seen = new java.util.HashSet<>(images.stream().map(HotelDetailResponse.Image::getUrl).toList());
        roomTypes.values().forEach(r -> images(r.path("images")).forEach(img -> { if (seen.add(img.getUrl())) images.add(img); }));

        JsonNode address = i.path("locale").path("address");
        JsonNode coords = i.path("locale").path("coordinates");
        JsonNode times = i.path("policies").path("checkInCheckOut");
        List<HotelDetailResponse.RoomOption> options = new ArrayList<>();
        for (JsonNode o : pricing.path("options")) {
            Map<String, HotelDetailResponse.Room> rooms = new LinkedHashMap<>();
            for (JsonNode ri : o.path("roomInfo")) {
                String id = ri.path("id").asText();
                HotelDetailResponse.Room known = rooms.get(id + "|" + ri.path("name").asText());
                if (known != null) { known.setCount(known.getCount() + 1); continue; }
                rooms.put(id + "|" + ri.path("name").asText(), room(id, ri.path("name").asText(), roomTypes.get(id)));
            }
            boolean refundable = o.path("cancellation").path("isRefundable").asBoolean(false);
            options.add(HotelDetailResponse.RoomOption.builder()
                    .optionId(o.path("optionId").asText())
                    .rooms(new ArrayList<>(rooms.values()))
                    .mealBasis(o.path("mealBasis").asText(""))
                    .inclusions(strings(o.path("inclusions")))
                    .bookingNotes(text(o.path("bookingNotes").asText("")))
                    .price(new BigDecimal(o.path("pricing").path("totalPrice").asText("0")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .currency(o.path("pricing").path("currency").asText("INR"))
                    .refundable(refundable)
                    .cancellationPolicy(cancellationPolicy(o.path("cancellation"), refundable))
                    .build());
        }
        options.sort(Comparator.comparing(HotelDetailResponse.RoomOption::getPrice));

        List<HotelDetailResponse.Section> policies = new ArrayList<>();
        for (String[] p : new String[][]{{"mandatory_fees", "Fees paid at the hotel"}, {"know_before_you_go", "Know before you go"},
                {"instructions", "Check-in instructions"}, {"special_instructions", "Special instructions"}, {"optional_fees", "Optional fees"}}) {
            String t = flatText(i.path("policies").path(p[0]));
            if (!t.isBlank()) policies.add(HotelDetailResponse.Section.builder().title(p[1]).text(t).build());
        }
        List<HotelDetailResponse.Section> descriptions = new ArrayList<>();
        i.path("descriptions").fields().forEachRemaining(e -> {
            String t = flatText(e.getValue());
            if (!t.isBlank()) descriptions.add(HotelDetailResponse.Section.builder().title(title(e.getKey())).text(t).build());
        });
        List<String> amenities = new ArrayList<>();
        i.path("amenities").forEach(a -> { String n = a.path("name").asText(""); if (!n.isBlank()) amenities.add(n); });

        return HotelDetailResponse.builder()
                .hotelId(hotelId)
                .name(!pricing.path("hotelName").asText("").isBlank() ? pricing.path("hotelName").asText() : i.path("name").asText(""))
                .starRating((int) Math.round(i.path("star_rating").asDouble(0)))
                .address(address.path("fulladdr").asText(""))
                .city(!address.path("city").asText("").isBlank() ? address.path("city").asText() : city)
                .latitude(coords.has("lat") ? coords.path("lat").decimalValue() : null)
                .longitude(coords.has("long") ? coords.path("long").decimalValue() : null)
                .images(images)
                .descriptions(descriptions)
                .amenities(amenities)
                .checkInFrom(times.path("checkin_from").asText(null))
                .checkOutTill(times.path("checkout_till").asText(null))
                .policies(policies)
                .options(options)
                .nights(nights)
                .supplier("Tripjack")
                .build();
    }

    private static HotelDetailResponse.Room room(String id, String name, JsonNode type) {
        HotelDetailResponse.Room.RoomBuilder b = HotelDetailResponse.Room.builder().id(id).name(name).count(1)
                .amenities(List.of()).images(List.of());
        if (type == null) return b.build();
        List<String> views = new ArrayList<>();
        type.path("views").forEach(v -> views.add(v.path("name").asText()));
        List<String> amenities = new ArrayList<>();
        type.path("amenities").forEach(a -> amenities.add(a.path("name").asText()));
        JsonNode sqft = type.path("area").path("square_feet");
        JsonNode max = type.path("occupancy").path("max_allowed").path("total");
        return b.beds(type.path("bed_config").path("description").asText(null))
                .areaSqft(sqft.isNumber() || sqft.isTextual() ? (int) Math.round(sqft.asDouble()) : null)
                .view(views.isEmpty() ? null : String.join(", ", views))
                .maxOccupancy(max.isNumber() || max.isTextual() ? max.asInt() : null)
                .description(text(type.path("descriptions").path("overview").asText("")))
                .amenities(amenities)
                .images(images(type.path("images")))
                .build();
    }

    /** Photos with the main one first; each at its largest size, and a mid size for thumbnails. */
    static List<HotelDetailResponse.Image> images(JsonNode images) {
        List<HotelDetailResponse.Image> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode img : images) {
            String url = link(img.path("links"), true);
            if (url == null || !seen.add(url)) continue;
            HotelDetailResponse.Image image = HotelDetailResponse.Image.builder()
                    .url(url).thumbUrl(link(img.path("links"), false)).caption(img.path("caption").asText(null)).build();
            if (img.path("is_hero_image").asBoolean(false) || img.path("hero_image").asBoolean(false)) out.add(0, image); else out.add(image);
        }
        return out;
    }

    /** The largest (or a thumbnail-sized) href among a photo's sizes: named ones (original, XXL, Standard) or "350px"-style widths. */
    static String link(JsonNode links, boolean largest) {
        List<String> order = largest
                ? List.of("original", "XXL", "XL", "1000px", "Standard", "350px", "200px", "100px", "70px")
                : List.of("350px", "Standard", "200px", "1000px", "XXL", "XL", "original", "100px", "70px");
        for (String k : order) {
            String href = links.path(k).path("href").asText("");
            if (!href.isBlank()) return href;
        }
        for (JsonNode l : links) {
            String href = l.path("href").asText("");
            if (!href.isBlank()) return href;
        }
        return null;
    }

    private JsonNode staticDetail(RestClient client, String host, String hotelId) {
        Cached<JsonNode> c = statics.get(host + "|" + hotelId);
        if (c != null && c.at().plus(STATIC_DETAIL_TTL).isAfter(Instant.now())) return c.value();
        try {
            JsonNode res = call(client, "POST", "/hms/v3/hotel/static-detail", Map.of("hid", hotelId));
            statics.put(host + "|" + hotelId, new Cached<>(res, Instant.now()));
            return res;
        } catch (SupplierException e) {
            log.warn("Tripjack hotel static detail failed for {}, showing the options without it: {}", hotelId, e.getMessage());
            return null;
        }
    }

    /** Supplier text is HTML (and sometimes a JSON object of named paragraphs); the page shows plain text only. */
    static String flatText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isObject()) {
            List<String> parts = new ArrayList<>();
            value.fields().forEachRemaining(e -> { String t = flatText(e.getValue()); if (!t.isBlank()) parts.add(t); });
            return String.join("\n", parts);
        }
        String raw = value.asText("").trim();
        if (raw.startsWith("{")) {
            try {
                return flatText(OBJECT_MAPPER.readTree(raw));
            } catch (Exception ignored) {
                // Not JSON after all - fall through to plain text.
            }
        }
        return text(raw);
    }

    static String text(String html) {
        if (html == null || html.isBlank()) return "";
        String t = html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</div>", "\n").replaceAll("<[^>]+>", " ");
        t = org.springframework.web.util.HtmlUtils.htmlUnescape(t);
        return t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{2,}", "\n").trim();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> { String v = n.asText(""); if (!v.isBlank()) out.add(v); });
        return out;
    }

    private static String title(String key) {
        String t = key.replace('_', ' ').trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
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

    /** Star rating, address and photos; a failure only costs the listing those, never the search. Batches go out together. */
    private Map<String, JsonNode> contentFor(RestClient client, String host, List<String> hids) {
        Map<String, JsonNode> out = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String hid : hids) {
            if (hid == null || hid.isBlank()) continue;
            Cached<JsonNode> c = content.get(host + "|" + hid);
            if (c != null && c.at().plus(CITY_INDEX_TTL).isAfter(Instant.now())) out.put(hid, c.value()); else missing.add(hid);
        }
        List<java.util.concurrent.CompletableFuture<Void>> calls = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += LISTING_BATCH) {
            List<String> batch = missing.subList(i, Math.min(i + LISTING_BATCH, missing.size()));
            calls.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    JsonNode res = call(client, "POST", "/hms/v3/content/fetch-hotel-content", Map.of("hotelIds", batch));
                    for (JsonNode h : res.path("hotels")) {
                        String hid = hotelIdOf(h);
                        if (hid.isBlank()) continue;
                        out.put(hid, h);
                        content.put(host + "|" + hid, new Cached<>(h, Instant.now()));
                    }
                } catch (SupplierException e) {
                    log.warn("Tripjack hotel content failed, listing without stars/address/photos: {}", e.getMessage());
                }
            }, CALLS));
        }
        calls.forEach(java.util.concurrent.CompletableFuture::join);
        return out;
    }

    /**
     * A hotel's Tripjack id. The docs name it tjHotelId, but live responses have come back without it - so the
     * other names it goes by are tried too, never sending a blank id on.
     */
    static String hotelIdOf(JsonNode hotel) {
        for (String key : List.of("tjHotelId", "hid", "hotelId", "id", "tjHotelID")) {
            String v = hotel.path(key).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    /** Builds the city index (the slow part of a cold start) in the background, so the first search doesn't wait on it. */
    @Override
    public void warm() {
        Conn conn = connect();
        CALLS.execute(() -> {
            try {
                cityIndex(conn.client(), conn.host());
                nationalityId(conn.client(), conn.host(), "IN");
            } catch (RuntimeException e) {
                log.warn("Tripjack hotel warm-up failed: {}", e.getMessage());
            }
        });
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
