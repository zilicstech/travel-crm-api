package com.voyra.crm.service.tripjack;

import com.voyra.crm.service.FlightResultPaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightLegResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.enums.FlightDirection;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.FlightSearchProvider;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.util.AirportCodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Tripjack flight search, behind {@code app.flight-supplier.provider=tripjack}.
 *
 * <p>The request/response contract below is reconstructed from Tripjack's own published
 * documentation (endpoint table + Postman setup instructions) and cross-checked against two
 * independent live integrations - a published Flutter app and an MCP server - that both call
 * this exact endpoint with this exact shape. Confirmed live against a real UAT call (an
 * invalid-key response - see {@code flight-search-error-response.json}): base URL, auth
 * header, endpoint path, JSON parsing (Tripjack answers with a Content-Type that doesn't
 * match its actual JSON body, so the response is read as a raw string and parsed here rather
 * than left to content negotiation), and the functional-error envelope
 * ({@code errors[0].message}, not {@code status.message} as first assumed). Two things
 * remain unconfirmed pending a successful (not just an error) response: the exact format of
 * {@code dt}/{@code at} (handled defensively - see {@link #parseFlightTime}) and the
 * non-ECONOMY {@code cabinClass} string values (only "ECONOMY" was seen in a real call; the
 * others follow the same enum by symmetry).
 */
@Service
@ConditionalOnProperty(name = "app.flight-supplier.provider", havingValue = "tripjack")
@RequiredArgsConstructor
@Slf4j
public class TripjackFlightSearchProvider implements FlightSearchProvider {

    private final PlatformSupplierCredentialService platformSupplierCredentialService;
    /** The timeout-bounded builder from {@code config.TripjackConfig} - cloned per call so
     *  a credential change applies to the next call. This class never calls
     *  {@code .requestFactory(...)} on it, so tests can bind {@code MockRestServiceServer} to
     *  a plain builder instead without their mock being silently overwritten. */
    @Qualifier("tripjackRestClientBuilder")
    private final RestClient.Builder restClientBuilder;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The client {@link #outboundIp()} asks; tests swap it for a mock so they never reach the network. */
    RestClient ipLookup = timeoutBoundClient();

    private static RestClient timeoutBoundClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(3_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        // The platform credential is resolved fresh per call, so a key change applies to the next search.
        log.info("Tripjack flight search start: {} -> {} on {}", query.getOrigin(), query.getDestination(), query.getDepartDate());
        ResolvedCredential credential;
        try {
            credential = platformSupplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        } catch (RuntimeException e) {
            log.warn("Tripjack flight search aborted - no active platform credential: {}", e.getMessage());
            throw e;
        }
        return search(query, credential);
    }

    /** Searches with the given credential instead of the active one - the supplier page's Test connection tests the row it is on. */
    public List<FlightOfferResponse> search(FlightSearchQuery query, ResolvedCredential credential) {
        if (credential != null) {
            String key = credential.apiKey() == null ? "" : credential.apiKey();
            log.info("Tripjack credential resolved: environment={}, baseUrl={}, apiKey=...{} (len {})",
                    credential.environment(), credential.baseUrl(),
                    key.length() > 4 ? key.substring(key.length() - 4) : "****", key.length());
        }

        String originCode = AirportCodeResolver.resolve(query.getOrigin());
        String destinationCode = AirportCodeResolver.resolve(query.getDestination());

        long startedAt = System.currentTimeMillis();
        JsonNode response = callSearch(credential, buildRequestBody(query, originCode, destinationCode));
        List<FlightOfferResponse> offers = toOffers(response, originCode, destinationCode, query);
        log.info("Tripjack flight search ok: {} offers in {} ms", offers.size(), System.currentTimeMillis() - startedAt);
        return offers;
    }

    private Map<String, Object> buildRequestBody(FlightSearchQuery query, String originCode, String destinationCode) {
        Map<String, Object> paxInfo = new LinkedHashMap<>();
        paxInfo.put("ADULT", Math.max(1, query.getAdults()));
        if (query.getChildren() > 0) {
            paxInfo.put("CHILD", query.getChildren());
        }
        if (query.getInfants() > 0) {
            paxInfo.put("INFANT", query.getInfants());
        }

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("fromCityOrAirport", Map.of("code", originCode));
        route.put("toCityOrAirport", Map.of("code", destinationCode));
        route.put("travelDate", query.getDepartDate().toString());

        List<Map<String, Object>> routes = new ArrayList<>(List.of(route));
        if (query.getReturnDate() != null) {
            // a round trip is a second route back; Tripjack answers ONWARD + RETURN (domestic) or COMBO (international)
            Map<String, Object> back = new LinkedHashMap<>();
            back.put("fromCityOrAirport", Map.of("code", destinationCode));
            back.put("toCityOrAirport", Map.of("code", originCode));
            back.put("travelDate", query.getReturnDate().toString());
            routes.add(back);
        }

        Map<String, Object> searchQuery = new LinkedHashMap<>();
        searchQuery.put("cabinClass", query.getCabin() != null ? query.getCabin().name() : "ECONOMY");
        searchQuery.put("paxInfo", paxInfo);
        searchQuery.put("routeInfos", routes);

        return Map.of("searchQuery", searchQuery);
    }

    private JsonNode callSearch(ResolvedCredential credential, Map<String, Object> requestBody) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(credential.baseUrl())
                .defaultHeader("apikey", credential.apiKey())
                .build();

        // Read as a raw string rather than retrieve().body(JsonNode.class) - Tripjack has
        // been observed answering with a Content-Type that doesn't match its actual JSON
        // body (e.g. application/octet-stream), which makes Spring's message-converter
        // negotiation refuse to parse a perfectly valid response. Parsing it ourselves
        // sidesteps that header entirely.
        String raw;
        try {
            raw = client.post()
                    .uri("/fms/v1/air-search-all")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 401/403 here usually means this server's public IP is not whitelisted by Tripjack
            // (or the key is wrong) - log the body so that is diagnosable without a debugger.
            log.error("Tripjack flight search HTTP {} from {}: {}. Outbound IP of this server as seen "
                            + "externally: {} (this is the IP Tripjack must have whitelisted)",
                    e.getStatusCode().value(), credential.baseUrl(), e.getResponseBodyAsString(), outboundIp());
            throw new SupplierException("Tripjack flight search call failed (HTTP "
                    + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.error("Tripjack flight search could not reach {} ({}: {}). Outbound IP of this server: {}",
                    credential.baseUrl(), e.getClass().getSimpleName(), e.getMessage(), outboundIp());
            throw new SupplierException("Tripjack flight search call failed", e);
        }

        JsonNode response;
        try {
            response = raw != null ? OBJECT_MAPPER.readTree(raw) : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Tripjack flight search returned a non-JSON body: {}", raw);
            throw new SupplierException("Tripjack flight search returned an unparseable response", e);
        }

        if (response == null || !response.path("status").path("success").asBoolean(false)) {
            log.error("Tripjack flight search did not succeed. Full response: {}", raw);
            String message = response != null
                    ? response.path("errors").path(0).path("message").asText("Search failed")
                    : "Empty response";
            throw new SupplierException("Tripjack flight search returned an error: " + message, null);
        }
        return response;
    }

    /** Best-effort lookup of this server's public egress IP, called only on a failure so the
     *  value to whitelist is in the log. Never throws and never carries a credential. */
    String outboundIp() {
        try {
            String ip = ipLookup.get().uri("https://api.ipify.org").retrieve().body(String.class);
            return ip == null ? "unknown" : ip.trim();
        } catch (RuntimeException e) {
            return "unknown (lookup failed: " + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * One offer per fare of every trip under {@code tripInfos}: ONWARD (also one-way), RETURN (domestic round trip, paired by the agency)
     * and COMBO (international round trip, both ways in one fare; the way back is the segments flagged {@code isRs}).
     * Shapes are from Tripjack's documentation, to be corrected by the slice 0 spike (the probe got 403 from this machine).
     */
    private List<FlightOfferResponse> toOffers(JsonNode response, String originCode, String destinationCode,
                                             FlightSearchQuery query) {
        List<FlightOfferResponse> offers = new ArrayList<>();
        JsonNode tripInfos = response.path("searchResult").path("tripInfos");
        for (FlightDirection direction : FlightDirection.values()) {
            boolean back = direction == FlightDirection.RETURN;
            java.time.LocalDate searchDate = back ? query.getReturnDate() : query.getDepartDate();
            for (JsonNode trip : tripInfos.path(direction.name())) {
                JsonNode segments = trip.path("sI");
                if (!segments.isArray() || segments.isEmpty()) {
                    continue;
                }
                List<JsonNode> out = new ArrayList<>();
                List<JsonNode> ret = new ArrayList<>();
                segments.forEach(seg -> (direction == FlightDirection.COMBO && seg.path("isRs").asBoolean(false) ? ret : out).add(seg));
                List<FlightLegResponse> legs = new ArrayList<>();
                legs.add(leg(out, back ? destinationCode : originCode, back ? originCode : destinationCode, searchDate));
                if (!ret.isEmpty()) {
                    legs.add(leg(ret, destinationCode, originCode, query.getReturnDate()));
                }
                FlightLegResponse first = legs.get(0);

                for (JsonNode fare : trip.path("totalPriceList")) {
                    JsonNode adultFare = fare.path("fd").path("ADULT");
                    // fd.<TYPE>.fC.TF is the fare for ONE passenger of that type, not the party.
                    BigDecimal adultPrice = new BigDecimal(adultFare.path("fC").path("TF").asText("0"));
                    List<String> pairsWith = new ArrayList<>();
                    fare.path("msri").forEach(id -> pairsWith.add(id.asText()));
                    offers.add(FlightOfferResponse.builder()
                            .offerId(fare.path("id").asText())
                            .direction(direction)
                            .legs(legs)
                            .airline(first.getAirline())
                            .airlineCode(first.getAirlineCode())
                            .flightNumber(first.getFlightNumber())
                            .origin(first.getOrigin())
                            .destination(first.getDestination())
                            .departTime(first.getDepartTime())
                            .arriveTime(first.getArriveTime())
                            .departDate(first.getDepartDate())
                            .arriveDate(first.getArriveDate())
                            .duration(first.getDuration())
                            .stops(first.getStops())
                            .supplier("Tripjack")
                            .price(adultPrice)
                            .totalPrice(FlightSearchProvider.partyTotal(query, adultPrice,
                                    paxFare(fare, "CHILD"), paxFare(fare, "INFANT")))
                            .baseFare(baseFare(fare, "ADULT") == null ? null : FlightSearchProvider.partyTotal(query,
                                    baseFare(fare, "ADULT"), baseFare(fare, "CHILD"), baseFare(fare, "INFANT")))
                            .passengers(FlightSearchProvider.passengers(query))
                            .currency("INR")
                            .refundable("REFUNDABLE".equalsIgnoreCase(adultFare.path("rT").asText("")))
                            .baggage(adultFare.path("bI").path("iB").asText(""))
                            .fareType(fare.path("fareIdentifier").asText("Standard"))
                            .specialReturnId(fare.path("sri").isMissingNode() || fare.path("sri").asText().isBlank() ? null : fare.path("sri").asText())
                            .pairsWith(pairsWith)
                            .build());
                }
            }
        }
        return offers;
    }

    /** One direction from its segments: first segment's carrier and departure, last segment's arrival, stops and journey time across them. */
    private FlightLegResponse leg(List<JsonNode> segments, String originCode, String destinationCode, java.time.LocalDate searchDate) {
        JsonNode firstSegment = segments.get(0);
        JsonNode lastSegment = segments.get(segments.size() - 1);
        int stops = segments.size() - 1;
        int journeyMinutes = 0;
        for (int i = 0; i < segments.size(); i++) {
            JsonNode seg = segments.get(i);
            stops += seg.path("stops").asInt(0); // technical stops within one leg
            int legMinutes = FlightResultPaging.durationMinutes(seg.path("duration").asText(""));
            journeyMinutes = legMinutes == Integer.MAX_VALUE || journeyMinutes < 0 ? -1 : journeyMinutes + legMinutes;
            if (i > 0 && journeyMinutes >= 0) {
                // Layover: both times are local to the same connecting airport, so the gap is time-zone safe.
                Long layover = minutesBetween(segments.get(i - 1).path("at").asText(""), seg.path("dt").asText(""));
                journeyMinutes = layover == null ? -1 : journeyMinutes + layover.intValue();
            }
        }
        LocalTime departTime = parseFlightTime(firstSegment.path("dt").asText(""));
        LocalTime arriveTime = parseFlightTime(lastSegment.path("at").asText(""));
        java.time.LocalDate departDate = parseFlightDate(firstSegment.path("dt").asText(""), searchDate);
        return FlightLegResponse.builder()
                .airline(firstSegment.path("fD").path("aI").path("name").asText(""))
                .airlineCode(firstSegment.path("fD").path("aI").path("code").asText(""))
                .flightNumber(firstSegment.path("fD").path("fN").asText(""))
                .origin(firstSegment.path("da").path("code").asText(originCode))
                .destination(lastSegment.path("aa").path("code").asText(destinationCode))
                .departTime(departTime)
                .arriveTime(arriveTime)
                .departDate(departDate)
                .arriveDate(parseFlightDate(lastSegment.path("at").asText(""),
                        departDate == null ? null : arriveTime.isBefore(departTime) ? departDate.plusDays(1) : departDate))
                .duration(journeyMinutes >= 0 ? String.valueOf(journeyMinutes) : firstSegment.path("duration").asText(""))
                .stops(stops)
                .build();
    }

    /** fd.<TYPE>.fC.BF: the commissionable base for ONE passenger; a missing BF is null, so an offer without an adult BF has no base and is not bookable. */
    private static BigDecimal baseFare(JsonNode fare, String paxType) {
        JsonNode bf = fare.path("fd").path(paxType).path("fC").path("BF");
        return bf.isMissingNode() || bf.asText().isBlank() ? null : new BigDecimal(bf.asText());
    }

    private static BigDecimal paxFare(JsonNode fare, String paxType) {
        JsonNode tf = fare.path("fd").path(paxType).path("fC").path("TF");
        return tf.isMissingNode() || tf.asText().isBlank() ? null : new BigDecimal(tf.asText());
    }

    private static Long minutesBetween(String from, String to) {
        try {
            return java.time.Duration.between(java.time.LocalDateTime.parse(from), java.time.LocalDateTime.parse(to)).toMinutes();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})");

    /**
     * Tripjack's real datetime format for {@code dt}/{@code at} was not present in any
     * source available while building this - only that the fields exist. Tries the two most
     * likely shapes (a full ISO datetime, or a bare time), then falls back to pulling an
     * HH:mm substring out of whatever is actually there, rather than silently returning
     * midnight for a field the UI displays directly to an agent.
     */
    /** The date part of a segment's ISO datetime; {@code fallback} when the supplier sent a bare time. */
    static java.time.LocalDate parseFlightDate(String raw, java.time.LocalDate fallback) {
        try {
            return LocalDateTime.parse(raw).toLocalDate();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private LocalTime parseFlightTime(String raw) {
        if (raw.isBlank()) {
            throw new SupplierException("Tripjack flight response is missing a departure/arrival time", null);
        }
        try {
            return LocalDateTime.parse(raw).toLocalTime();
        } catch (DateTimeParseException ignored) {
            // not a full ISO datetime - fall through
        }
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // not a bare ISO time either - fall through
        }
        Matcher matcher = TIME_PATTERN.matcher(raw);
        if (matcher.find()) {
            return LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        throw new SupplierException("Could not parse Tripjack flight time: \"" + raw + "\"", null);
    }
}
