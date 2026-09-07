package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.FlightSearchProvider;
import com.voyra.crm.service.SupplierCredentialService;
import com.voyra.crm.util.AirportCodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    private final SupplierCredentialService supplierCredentialService;
    /** The timeout-bounded builder from {@code config.TripjackConfig} - cloned per call so
     *  each agency's base URL/key never leaks onto a shared client. This class never calls
     *  {@code .requestFactory(...)} on it, so tests can bind {@code MockRestServiceServer} to
     *  a plain builder instead without their mock being silently overwritten. */
    @Qualifier("tripjackRestClientBuilder")
    private final RestClient.Builder restClientBuilder;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public List<FlightOfferResponse> search(FlightSearchQuery query) {
        // Credentials resolved fresh per call, never cached on this singleton bean - it is
        // shared by every tenant, so caching here would serve one agency's key to all of them.
        SupplierCredentialRevealResponse credential =
                supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);

        String originCode = AirportCodeResolver.resolve(query.getOrigin());
        String destinationCode = AirportCodeResolver.resolve(query.getDestination());

        JsonNode response = callSearch(credential, buildRequestBody(query, originCode, destinationCode));
        return toOffers(response, originCode, destinationCode);
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

        Map<String, Object> searchQuery = new LinkedHashMap<>();
        searchQuery.put("cabinClass", query.getCabin() != null ? query.getCabin().name() : "ECONOMY");
        searchQuery.put("paxInfo", paxInfo);
        searchQuery.put("routeInfos", List.of(route));

        return Map.of("searchQuery", searchQuery);
    }

    private JsonNode callSearch(SupplierCredentialRevealResponse credential, Map<String, Object> requestBody) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(credential.getBaseUrl())
                .defaultHeader("apikey", credential.getApiKey())
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
        } catch (RestClientException e) {
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

    private List<FlightOfferResponse> toOffers(JsonNode response, String originCode, String destinationCode) {
        List<FlightOfferResponse> offers = new ArrayList<>();
        for (JsonNode trip : response.path("searchResult").path("tripInfos").path("ONWARD")) {
            JsonNode segments = trip.path("sI");
            if (!segments.isArray() || segments.isEmpty()) {
                continue;
            }
            JsonNode firstSegment = segments.get(0);
            JsonNode lastSegment = segments.get(segments.size() - 1);

            String airline = firstSegment.path("fD").path("aI").path("name").asText("");
            String flightNumber = firstSegment.path("fD").path("fN").asText("");
            int stops = segments.size() - 1;
            String duration = firstSegment.path("duration").asText("");
            LocalTime departTime = parseFlightTime(firstSegment.path("dt").asText(""));
            LocalTime arriveTime = parseFlightTime(lastSegment.path("at").asText(""));
            String origin = firstSegment.path("da").path("code").asText(originCode);
            String destination = lastSegment.path("aa").path("code").asText(destinationCode);

            for (JsonNode fare : trip.path("totalPriceList")) {
                JsonNode adultFare = fare.path("fd").path("ADULT");
                offers.add(FlightOfferResponse.builder()
                        .offerId(fare.path("id").asText())
                        .airline(airline)
                        .flightNumber(flightNumber)
                        .origin(origin)
                        .destination(destination)
                        .departTime(departTime)
                        .arriveTime(arriveTime)
                        .duration(duration)
                        .stops(stops)
                        .supplier("Tripjack")
                        .price(new BigDecimal(adultFare.path("fC").path("TF").asText("0")))
                        .currency("INR")
                        .refundable("REFUNDABLE".equalsIgnoreCase(adultFare.path("rT").asText("")))
                        .baggage(adultFare.path("bI").path("iB").asText(""))
                        .fareType(fare.path("fareIdentifier").asText("Standard"))
                        .build());
            }
        }
        return offers;
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})");

    /**
     * Tripjack's real datetime format for {@code dt}/{@code at} was not present in any
     * source available while building this - only that the fields exist. Tries the two most
     * likely shapes (a full ISO datetime, or a bare time), then falls back to pulling an
     * HH:mm substring out of whatever is actually there, rather than silently returning
     * midnight for a field the UI displays directly to an agent.
     */
    private LocalTime parseFlightTime(String raw) {
        if (raw == null || raw.isBlank()) {
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
