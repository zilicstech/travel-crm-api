package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tripjack flight booking, behind {@code app.flight-booking.provider=tripjack}.
 *
 * <p><b>Unverified until the slice 0 spike.</b> Endpoints and field names come from the
 * B2B design spec section 7.3 (review, air/book, booking-details, farerule, amendment), not
 * from a real booking response. Every field is read defensively - a missing field becomes a
 * null or an empty list, never an NPE - and the field mapping is expected to be corrected
 * once real fixtures exist. Transport conventions are copied from {@code TripjackFlightSearchProvider}:
 * raw-string reads (Tripjack's content type is unreliable) and an {@code apikey} header that is never logged.
 */
@Service
@ConditionalOnProperty(name = "app.flight-booking.provider", havingValue = "tripjack")
@RequiredArgsConstructor
@Slf4j
public class TripjackFlightBookingProvider implements FlightBookingProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PlatformSupplierCredentialService platformSupplierCredentialService;
    @Qualifier("tripjackRestClientBuilder")
    private final RestClient.Builder restClientBuilder;

    @Override
    public FareReview review(String offerId, int adults, int children, int infants) {
        JsonNode res = post("/fms/v1/review", Map.of("priceIds", List.of(offerId)), "review");
        if (!succeeded(res)) {
            throw new FareUnavailableException(message(res, "Fare is no longer available"));
        }
        JsonNode fare = res.path("totalPriceInfo").path("totalFareDetail").path("fC");
        String reviewId = text(res.path("bookingId"));
        BigDecimal total = decimal(fare.path("TF"));
        if (reviewId == null || reviewId.isBlank() || total == null) {
            throw new SupplierException("Tripjack review response is missing the review id or total fare", null);
        }
        return new FareReview(reviewId, total, decimal(fare.path("BF")), "INR",
                fareRules(reviewId), null, res.path("tripInfos").isMissingNode() ? null : res.path("tripInfos"));
    }

    @Override
    public BookResult book(BookRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", r.reviewId());
        body.put("paymentInfos", List.of(Map.of("amount", r.totalFare())));
        body.put("travellerInfo", r.passengers().stream().map(TripjackFlightBookingProvider::traveller).toList());
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("emails", r.email() == null ? List.of() : List.of(r.email()));
        delivery.put("contacts", r.phone() == null ? List.of() : List.of(r.phone()));
        body.put("deliveryInfo", delivery);

        JsonNode res = post("/oms/v1/air/book", body, "book", false);
        if (explicitlyFailed(res)) {
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), message(res, "Booking failed"), null);
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have booked, so this is unknown, not failed.
            throw new SupplierException("Tripjack book returned an ambiguous response", null);
        }
        // Accepted is not ticketed: the ticket arrives asynchronously, so the caller polls bookingDetails.
        return new BookResult(r.reviewId(), SupplierBookingStatus.PENDING, null, List.of(), null, null);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        JsonNode res = post("/oms/v1/booking-details", Map.of("bookingId", supplierBookingId), "booking-details");
        if (!succeeded(res)) {
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), message(res, null), null);
        }
        String orderStatus = res.path("order").path("status").asText("").toUpperCase();
        SupplierBookingStatus status = switch (orderStatus) {
            case "SUCCESS", "TICKETED" -> SupplierBookingStatus.TICKETED;
            case "FAILED", "CANCELLED" -> SupplierBookingStatus.FAILED;
            default -> SupplierBookingStatus.PENDING;
        };
        String pnr = null;
        List<String> tickets = new ArrayList<>();
        for (JsonNode traveller : res.path("itemInfos").path("AIR").path("travellerInfos")) {
            if (pnr == null) {
                pnr = firstValue(traveller.path("pnrDetails"));
            }
            traveller.path("ticketNumberDetails").fields().forEachRemaining(e -> {
                if (!e.getValue().asText("").isBlank()) {
                    tickets.add(e.getValue().asText());
                }
            });
        }
        // shortcut: the charged amount is not parsed (field unknown until the slice 0 spike), so no variance is detected here
        return new BookResult(supplierBookingId, status, pnr, tickets, orderStatus.isEmpty() ? null : orderStatus, null);
    }

    @Override
    public CancelQuote cancelQuote(String supplierBookingId) {
        JsonNode res = post("/oms/v1/air/amendment/amendment-charges", amendmentBody(supplierBookingId), "cancel quote");
        return new CancelQuote(refundOf(res), message(res, null));
    }

    @Override
    public CancelResult cancel(String supplierBookingId) {
        JsonNode res = post("/oms/v1/air/amendment/submit-amendment", amendmentBody(supplierBookingId), "cancel");
        return new CancelResult(succeeded(res), refundOf(res), message(res, null));
    }

    private String fareRules(String reviewId) {
        try {
            JsonNode res = post("/fms/v2/farerule", Map.of("id", reviewId == null ? "" : reviewId, "flowType", "REVIEW"), "farerule");
            JsonNode rules = res.path("fareRule");
            return rules.isMissingNode() || rules.isNull() ? null : rules.toString();
        } catch (SupplierException e) {
            log.warn("Tripjack fare rules unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> amendmentBody(String bookingId) {
        return Map.of("bookingId", bookingId, "type", "CANCELLATION", "remarks", "Cancelled by agency");
    }

    private static Map<String, Object> traveller(PaxDetail p) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ti", p.title());
        t.put("fN", p.firstName());
        t.put("lN", p.lastName());
        t.put("pt", p.type());
        putIfPresent(t, "dob", p.dateOfBirth());
        putIfPresent(t, "pNum", p.passportNumber());
        putIfPresent(t, "eD", p.passportExpiry());
        putIfPresent(t, "pNat", p.nationality());
        return t;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value.toString());
        }
    }

    /** True only for an explicit boolean {@code status.success=false}. */
    private static boolean explicitlyFailed(JsonNode res) {
        JsonNode flag = res == null ? null : res.path("status").path("success");
        return flag != null && flag.isBoolean() && !flag.booleanValue();
    }

    private static boolean succeeded(JsonNode res) {
        return res != null && res.path("status").path("success").asBoolean(false);
    }

    private static String message(JsonNode res, String fallback) {
        if (res == null) {
            return fallback;
        }
        String m = res.path("errors").path(0).path("message").asText("");
        return m.isBlank() ? fallback : m;
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static BigDecimal decimal(JsonNode n) {
        try {
            return n.isMissingNode() || n.isNull() ? null : new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal refundOf(JsonNode res) {
        return res == null ? null : decimal(res.path("totalRefundAmount"));
    }

    private static String firstValue(JsonNode map) {
        var it = map.elements();
        return it.hasNext() ? text(it.next()) : null;
    }

    private JsonNode post(String path, Object body, String what) {
        return post(path, body, what, true);
    }

    /** {@code logBody=false} for calls whose error body may echo passenger data. */
    private JsonNode post(String path, Object body, String what, boolean logBody) {
        ResolvedCredential credential = platformSupplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        RestClient client = restClientBuilder.clone()
                .baseUrl(credential.baseUrl())
                .defaultHeader("apikey", credential.apiKey())
                .build();
        String raw;
        try {
            raw = client.post().uri(path).body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            String text = logBody ? e.getResponseBodyAsString() : "";
            log.error("Tripjack {} HTTP {}: {}", what, e.getStatusCode().value(), text.length() > 200 ? text.substring(0, 200) : text);
            throw new SupplierException("Tripjack " + what + " call failed (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new SupplierTimeoutException("Tripjack " + what + " timed out", e);
            }
            throw new SupplierException("Tripjack " + what + " call failed", e);
        } catch (RestClientException e) {
            throw new SupplierException("Tripjack " + what + " call failed", e);
        }
        try {
            return raw == null ? null : OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new SupplierException("Tripjack " + what + " returned an unparseable response", e);
        }
    }
}
