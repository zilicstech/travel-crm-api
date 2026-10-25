package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.dto.HotelThumbnailResponse;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.HotelSearchProvider;
import com.voyra.crm.service.PlatformSupplierCredentialService;
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
 * content), the same for every agency, keyed by host - never a price and never a credential. The city list and
 * nationalities are never fetched while someone waits: {@link com.voyra.crm.scheduler.TripjackHotelStaticDataJob}
 * loads them at startup and again once they are a week old, and a search reads whatever is loaded.
 */
@Service
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "tripjack")
@Slf4j
public class TripjackHotelSearchProvider implements HotelSearchProvider {

    static final int LISTING_BATCH = 100;
    static final int MAX_BATCHES = 3;
    /** Content is asked a few hotels per call, all calls at once - a small call answers sooner than one of 20. */
    static final int CONTENT_BATCH = 5;
    static final int DEFAULT_CHILD_AGE = 8;
    static final Duration STATIC_DATA_TTL = Duration.ofDays(7);
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
    /** The city list and nationalities of one Tripjack host. */
    private record StaticData(Map<String, List<Region>> cities, Map<String, String> nationalities, Instant builtAt) { }
    private record Cached<T>(T value, Instant at) { }

    private final PlatformSupplierCredentialService platformSupplierCredentialService;
    private final RestClient.Builder restClientBuilder;
    private final String uatBaseUrl;
    private final String prodBaseUrl;

    private final Map<String, StaticData> staticData = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final Map<String, Cached<List<String>>> hotelIds = new ConcurrentHashMap<>();
    private final Map<String, Cached<JsonNode>> content = new ConcurrentHashMap<>();
    private final Map<String, Cached<JsonNode>> statics = new ConcurrentHashMap<>();
    private static final Duration STATIC_DETAIL_TTL = Duration.ofHours(24);
    /** Listing and content batches go out side by side, not one after another - most of a search's wait. */
    private static final java.util.concurrent.ExecutorService CALLS = java.util.concurrent.Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "tripjack-hotel");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean shapeLogged = new java.util.concurrent.atomic.AtomicBoolean();

    public TripjackHotelSearchProvider(PlatformSupplierCredentialService platformSupplierCredentialService,
                                       @Qualifier("tripjackRestClientBuilder") RestClient.Builder restClientBuilder,
                                       @Value("${app.hotel-supplier.tripjack.uat-base-url:https://apitest-hms.tripjack.com}") String uatBaseUrl,
                                       @Value("${app.hotel-supplier.tripjack.prod-base-url:https://hms-search.tripjack.com}") String prodBaseUrl) {
        this.platformSupplierCredentialService = platformSupplierCredentialService;
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

        StaticData data = loaded(conn);
        List<String> regions = regionIds(data, query.getCity(), query.getCountryCode());
        List<String> hids = hotelIdsFor(client, host, regions);
        if (hids.isEmpty()) {
            return List.of();
        }
        String nationality = nationalityId(data, "IN");
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
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(query.getCheckIn(), query.getCheckOut()));
        List<HotelOfferResponse> offers = withOptions.stream()
                .map(h -> toOffer(h, query.getCity(), nights, correlationId))
                .sorted(Comparator.comparing(HotelOfferResponse::getPrice))
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
        ResolvedCredential credential = platformSupplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        String host = credential.environment() == SupplierEnvironment.PROD ? prodBaseUrl : uatBaseUrl;
        return new Conn(restClientBuilder.clone().baseUrl(host).defaultHeader("apikey", credential.apiKey()).build(), host);
    }

    /**
     * One hotel of the listing, from the listing alone: the list never waits on Tripjack's content call. A photo, stars or
     * address show only when the listing itself carries them; the hotel's own page fetches everything else.
     */
    static HotelOfferResponse toOffer(JsonNode hotel, String city, int nights, String correlationId) {
        JsonNode best = null;
        for (JsonNode o : hotel.path("options")) {
            if (best == null || o.path("pricing").path("totalPrice").asDouble() < best.path("pricing").path("totalPrice").asDouble()) {
                best = o;
            }
        }
        Set<String> roomNames = new LinkedHashSet<>();
        best.path("roomInfo").forEach(r -> roomNames.add(r.path("name").asText()));
        boolean refundable = best.path("cancellation").path("isRefundable").asBoolean(false);
        JsonNode address = hotel.path("address");
        return HotelOfferResponse.builder()
                .offerId(best.path("optionId").asText())
                .hotelId(hotelIdOf(hotel).isBlank() ? null : hotelIdOf(hotel))
                .imageUrl(listingImage(hotel))
                .correlationId(correlationId)
                .hotelName(hotel.path("name").asText())
                .starRating((int) Math.round(firstNumber(hotel, "starRating", "star_rating", "rating")))
                .city(city)
                .address(address.isObject() ? address.path("fulladdr").asText(address.path("line_1").asText("")) : address.asText(""))
                .roomType(String.join(" / ", roomNames))
                .boardBasis(best.path("mealBasis").asText(""))
                .nights(nights)
                .supplier("Tripjack")
                .price(new BigDecimal(best.path("pricing").path("totalPrice").asText("0")).setScale(2, java.math.RoundingMode.HALF_UP))
                .baseFare(baseRate(best.path("pricing")))
                .currency(best.path("pricing").path("currency").asText("INR"))
                .refundable(refundable)
                .cancellationPolicy(cancellationPolicy(best.path("cancellation"), refundable))
                .build();
    }

    /**
     * The room rate excluding taxes of an option's {@code pricing} node, or null when the supplier does not give it
     * (never 0: the option is then not bookable). UNVERIFIED: the field names are guesses until a real hotel pricing
     * response is captured - a rate under one of the common names, else total minus a stated tax.
     */
    public static BigDecimal baseRate(JsonNode pricing) {
        for (String key : List.of("baseRate", "roomRate", "baseFare", "netRate")) {
            BigDecimal v = positive(pricing.path(key));
            if (v != null) return v.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal total = positive(pricing.path("totalPrice"));
        for (String key : List.of("totalTax", "taxes", "tax")) {
            JsonNode tax = pricing.path(key);
            if (total != null && tax.isNumber() && tax.decimalValue().signum() >= 0 && tax.decimalValue().compareTo(total) < 0) {
                return total.subtract(tax.decimalValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private static BigDecimal positive(JsonNode n) {
        if (n.isNumber() || (n.isTextual() && n.asText().matches("\\d+(\\.\\d+)?"))) {
            BigDecimal v = new BigDecimal(n.asText());
            return v.signum() > 0 ? v : null;
        }
        return null;
    }

    /** The listing's hero image, if it has one - an images array, or a plain url under one of the names it goes by. */
    static String listingImage(JsonNode hotel) {
        if (hotel.path("images").isArray() && hotel.path("images").size() > 0) {
            return images(hotel.path("images")).stream().findFirst().map(HotelDetailResponse.Image::getThumbUrl).orElse(null);
        }
        for (String key : List.of("heroImage", "hero_image", "image", "img", "imageUrl", "thumbnail", "thumbnailUrl")) {
            JsonNode v = hotel.path(key);
            String url = v.isObject() ? link(v.path("links").isObject() ? v.path("links") : v, false) : v.asText("");
            if (url == null && v.isObject()) url = v.path("href").asText(v.path("url").asText(""));
            if (url != null && url.startsWith("http")) return url;
        }
        return null;
    }

    private static double firstNumber(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isNumber() || (v.isTextual() && v.asText().matches("\\d+(\\.\\d+)?"))) return v.asDouble();
        }
        return 0;
    }

    public static String cancellationPolicy(JsonNode cancellation, boolean refundable) {
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
        body.put("nationality", nationalityId(loaded(conn), "IN"));
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
                    .baseFare(baseRate(o.path("pricing")))
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
        List<HotelDetailResponse.Section> descriptions = descriptions(i.path("descriptions"));
        List<String> amenities = new ArrayList<>();
        i.path("amenities").forEach(a -> { String n = a.path("name").asText(""); if (!n.isBlank()) amenities.add(n); });

        return HotelDetailResponse.builder()
                .hotelId(hotelId)
                .name(!pricing.path("hotelName").asText("").isBlank() ? pricing.path("hotelName").asText() : i.path("name").asText(""))
                .starRating((int) Math.round(i.path("star_rating").asDouble(0)))
                .address(address.path("fulladdr").asText(""))
                // The city searched, not Tripjack's locality (Morjim for a Goa hotel) - it fills the voucher's city.
                .city(city)
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

    private static final Map<String, String> DESCRIPTION_TITLES = Map.ofEntries(
            Map.entry("headline", "Overview"), Map.entry("default", "About the hotel"), Map.entry("location", "Location"),
            Map.entry("attractions", "What's nearby"), Map.entry("rooms", "Rooms"), Map.entry("amenities", "Facilities"),
            Map.entry("dining", "Dining"), Map.entry("business_amenities", "Business facilities"),
            Map.entry("spoken_languages", "Languages spoken"), Map.entry("renovations", "Renovations"),
            Map.entry("national_ratings", "Ratings"));
    private static final List<String> DESCRIPTION_ORDER = List.of("headline", "default", "location", "attractions", "rooms",
            "amenities", "dining", "business_amenities", "spoken_languages", "renovations", "national_ratings");

    /**
     * The hotel's description sections in reading order, each under a plain title. Tripjack repeats paragraphs across
     * its sections (the headline often holds the whole overview again), so a paragraph is shown once, where it first
     * appears; a section left empty is dropped.
     */
    static List<HotelDetailResponse.Section> descriptions(JsonNode node) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        node.fields().forEachRemaining(fields::add);
        fields.sort(Comparator.comparingInt(e -> {
            int at = DESCRIPTION_ORDER.indexOf(e.getKey());
            return at < 0 ? DESCRIPTION_ORDER.size() : at;
        }));
        Set<String> seen = new java.util.HashSet<>();
        List<HotelDetailResponse.Section> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : fields) {
            List<String> fresh = new ArrayList<>();
            for (String paragraph : flatText(e.getValue()).split("\n")) {
                String p = paragraph.trim();
                if (!p.isEmpty() && seen.add(p.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))) fresh.add(p);
            }
            if (!fresh.isEmpty()) {
                out.add(HotelDetailResponse.Section.builder()
                        .title(DESCRIPTION_TITLES.getOrDefault(e.getKey(), title(e.getKey()))).text(String.join("\n", fresh)).build());
            }
        }
        return out;
    }

    /**
     * Supplier text is HTML (and sometimes a JSON object of named paragraphs); the page shows plain text only. A short
     * one-line part of such an object keeps its name ("Languages spoken: Hindi, English"), so it isn't a stray line.
     */
    static String flatText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isObject()) {
            List<String> parts = new ArrayList<>();
            value.fields().forEachRemaining(e -> {
                String t = flatText(e.getValue());
                if (t.isBlank()) return;
                boolean label = !t.contains("\n") && t.length() <= 80;
                parts.add(label ? DESCRIPTION_TITLES.getOrDefault(e.getKey(), title(e.getKey())) + ": " + t : t);
            });
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

    private static List<String> regionIds(StaticData data, String city, String countryCode) {
        String key = city.trim().toUpperCase(Locale.ROOT);
        List<Region> matches = CITY_ALIASES.getOrDefault(key, List.of(key)).stream()
                .flatMap(name -> data.cities().getOrDefault(name, List.of()).stream()).toList();
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

    /**
     * This host's city list and nationalities, however old - a week-old copy is replaced in the background, never
     * while a search waits. Not loaded yet (just started, or the platform credential moved to another Tripjack environment): the
     * load starts in the background and the search is asked to retry.
     */
    private StaticData loaded(Conn conn) {
        StaticData data = staticData.get(conn.host());
        if (data != null) return data;
        if (loading.add(conn.host())) {
            CALLS.execute(() -> {
                try {
                    load(conn);
                } catch (RuntimeException e) {
                    log.warn("Tripjack hotel static data load failed for {}: {}", conn.host(), e.getMessage());
                }
            });
        }
        throw new IllegalStateException("Hotel search is still loading Tripjack's city list - try again in a minute");
    }

    /**
     * Loads the platform Tripjack host's city list and nationalities unless a copy under a week old is already held.
     * Called by the scheduled job.
     */
    public void refreshStaticData() {
        Conn conn = connect();
        StaticData current = staticData.get(conn.host());
        if (current != null && current.builtAt().plus(STATIC_DATA_TTL).isAfter(Instant.now())) return;
        if (!loading.add(conn.host())) return;
        load(conn);
    }

    /** The whole paged city list (Tripjack has no name filter) and the nationality ids; swapped in only once complete. */
    private void load(Conn conn) {
        try {
            long startedAt = System.currentTimeMillis();
            Map<String, List<Region>> cities = new HashMap<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode page = call(conn.client(), "GET", "/hms/v3/content/fetch-city-regionIds?limit=2000" + (cursor == null ? "" : "&cursor=" + cursor), null);
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
            Map<String, String> nationalities = new HashMap<>();
            call(conn.client(), "GET", "/hms/v3/nationality-info", null).path("nationalityInfos")
                    .forEach(n -> nationalities.put(n.path("code").asText("").toUpperCase(Locale.ROOT), n.path("countryId").asText()));
            staticData.put(conn.host(), new StaticData(cities, nationalities, Instant.now()));
            log.info("Tripjack hotel static data loaded: {} cities from {} pages, {} nationalities in {} ms ({})",
                    cities.size(), pages, nationalities.size(), System.currentTimeMillis() - startedAt, conn.host());
        } finally {
            loading.remove(conn.host());
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

    private static String nationalityId(StaticData data, String iso2) {
        String id = data.nationalities().get(iso2);
        if (id == null) {
            throw new SupplierException("Tripjack hotels don't support nationality " + iso2, null);
        }
        return id;
    }

    /** Star rating, address and photos, cached a week; a failure only costs the list those. Batches go out together. */
    private Map<String, JsonNode> contentFor(RestClient client, String host, List<String> hids) {
        Map<String, JsonNode> out = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String hid : hids) {
            if (hid == null || hid.isBlank()) continue;
            Cached<JsonNode> c = content.get(host + "|" + hid);
            if (c != null && c.at().plus(STATIC_DATA_TTL).isAfter(Instant.now())) out.put(hid, c.value()); else missing.add(hid);
        }
        List<java.util.concurrent.CompletableFuture<Void>> calls = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += CONTENT_BATCH) {
            List<String> batch = missing.subList(i, Math.min(i + CONTENT_BATCH, missing.size()));
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

    @Override
    public List<HotelThumbnailResponse> thumbnails(List<String> hids) {
        Conn conn = connect();
        Map<String, JsonNode> info = contentFor(conn.client(), conn.host(), hids);
        return hids.stream().filter(info::containsKey).map(hid -> toThumbnail(hid, info.get(hid))).toList();
    }

    static HotelThumbnailResponse toThumbnail(String hid, JsonNode info) {
        JsonNode address = info.path("locale").path("address");
        return HotelThumbnailResponse.builder()
                .hotelId(hid)
                .imageUrl(images(info.path("images")).stream().findFirst().map(i -> small(i.getThumbUrl())).orElse(null))
                .starRating((int) Math.round(info.path("star_rating").asDouble(0)))
                .address(address.path("fulladdr").asText(String.join(", ", java.util.stream.Stream.of(
                        address.path("line_1").asText(""), address.path("city").asText("")).filter(v -> !v.isBlank()).toList())))
                .build();
    }

    /** Tripjack's photo host serves each photo in sizes by suffix: _z is ~1000px (~280 KB), _b 350px (~25 KB) - enough for a list card. */
    static String small(String url) {
        return url == null ? null : url.replaceFirst("^(https://i\\.travelapi\\.com/.+)_[a-z](\\.jpe?g)$", "$1_b$2");
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
