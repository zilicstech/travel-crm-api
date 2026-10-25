package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tripjack hotel booking, behind {@code app.hotel-booking.provider=tripjack}, on the hotel host of
 * {@code app.hotel-supplier.tripjack.*}.
 *
 * <p><b>Unverified.</b> No real hotel booking response has been captured. The endpoints
 * ({@code /hms/v3/hotel/review}, {@code /oms/v3/hotel/book}, {@code booking-details}, {@code cancel-booking}) and every
 * field name below are best guesses modelled on the flight booking shapes and the hotel search response; each is read
 * defensively (missing becomes null, never an NPE or a 0) and the mapping is to be corrected once real fixtures exist.
 * Same rulings as the flight provider: FAILED only on an explicit {@code status.success=false}; an empty or odd body is
 * a {@link SupplierException}; guessed statuses are PENDING; an unknown refund is null; book error bodies are never
 * logged (guest PII), others are cut to 200 characters. The cancel quote reads booking-details and never calls
 * cancel-booking, because a guessed dry-run flag could really cancel.
 */
@Service
@ConditionalOnProperty(name = "app.hotel-booking.provider", havingValue = "tripjack")
@Slf4j
public class TripjackHotelBookingProvider implements HotelBookingProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** shortcut: Tripjack deadlines carry no zone in the search shape, read as India time; correct after the spike. */
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Kolkata");

    private final PlatformSupplierCredentialService platformSupplierCredentialService;
    private final RestClient.Builder restClientBuilder;
    private final String uatBaseUrl;
    private final String prodBaseUrl;

    public TripjackHotelBookingProvider(PlatformSupplierCredentialService platformSupplierCredentialService,
                                        @Qualifier("tripjackRestClientBuilder") RestClient.Builder restClientBuilder,
                                        @Value("${app.hotel-supplier.tripjack.uat-base-url:https://apitest-hms.tripjack.com}") String uatBaseUrl,
                                        @Value("${app.hotel-supplier.tripjack.prod-base-url:https://hms-search.tripjack.com}") String prodBaseUrl) {
        this.platformSupplierCredentialService = platformSupplierCredentialService;
        this.restClientBuilder = restClientBuilder;
        this.uatBaseUrl = uatBaseUrl;
        this.prodBaseUrl = prodBaseUrl;
    }

    @Override
    public FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("optionId", optionId);
        body.put("hid", hotelId);
        body.put("checkIn", checkIn.toString());
        body.put("checkOut", checkOut.toString());
        body.put("roomCount", rooms);
        JsonNode res = post("/hms/v3/hotel/review", body, "review", true);
        if (!succeeded(res)) {
            throw new FareUnavailableException(message(res, "Room is no longer available"));
        }
        JsonNode option = res.path("option").isObject() ? res.path("option") : res.path("options").path(0);
        JsonNode pricing = option.path("pricing");
        String reviewId = text(res.path("bookingId"));
        BigDecimal total = decimal(pricing.path("totalPrice"));
        if (reviewId == null || reviewId.isBlank() || total == null) {
            throw new SupplierException("Tripjack hotel review response is missing the review id or total", null);
        }
        JsonNode cancellation = option.path("cancellation");
        boolean refundable = cancellation.path("isRefundable").asBoolean(false);
        String notes = text(option.path("bookingNotes"));
        return new FareReview(reviewId, total, TripjackHotelSearchProvider.baseRate(pricing), pricing.path("currency").asText("INR"),
                notes == null || notes.isBlank() ? null : notes, null, option.isMissingNode() ? null : option,
                TripjackHotelSearchProvider.cancellationPolicy(cancellation, refundable), refundable ? freeUntil(cancellation) : null);
    }

    @Override
    public BookResult book(BookRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", r.reviewId());
        body.put("paymentInfos", List.of(Map.of("amount", r.totalFare())));
        // shortcut: all guests go in one room entry (BookRequest has no room split); split per room once the real shape is known
        body.put("roomTravellerInfo", List.of(Map.of("travellerInfo", r.passengers().stream().map(TripjackHotelBookingProvider::traveller).toList())));
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("emails", r.email() == null ? List.of() : List.of(r.email()));
        delivery.put("contacts", r.phone() == null ? List.of() : List.of(r.phone()));
        body.put("deliveryInfo", delivery);

        JsonNode res = post("/oms/v3/hotel/book", body, "book", false);
        if (explicitlyFailed(res)) {
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), message(res, "Booking failed"), null);
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have booked, so this is unknown, not failed.
            throw new SupplierException("Tripjack hotel book returned an ambiguous response", null);
        }
        // Accepted is not confirmed: the caller polls bookingDetails.
        return new BookResult(r.reviewId(), SupplierBookingStatus.PENDING, null, List.of(), null, null);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        JsonNode res = post("/oms/v3/hotel/booking-details", Map.of("bookingId", supplierBookingId), "booking-details", true);
        if (!succeeded(res)) {
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), message(res, null), null);
        }
        String orderStatus = res.path("order").path("status").asText("").toUpperCase();
        SupplierBookingStatus status = switch (orderStatus) {
            case "SUCCESS", "CONFIRMED" -> SupplierBookingStatus.TICKETED;
            case "FAILED", "CANCELLED" -> SupplierBookingStatus.FAILED;
            default -> SupplierBookingStatus.PENDING;
        };
        String confirmation = firstText(res.path("itemInfos").path("HOTEL").path("confirmationNumber"),
                res.path("itemInfos").path("HOTEL").path("hotelInfo").path("confirmationNumber"), res.path("order").path("confirmationNumber"));
        // shortcut: the charged amount is not parsed (field unknown), so no variance is detected here
        return new BookResult(supplierBookingId, status, confirmation, List.of(), orderStatus.isEmpty() ? null : orderStatus, null);
    }

    @Override
    public CancelQuote cancelQuote(String supplierBookingId) {
        JsonNode res = post("/oms/v3/hotel/booking-details", Map.of("bookingId", supplierBookingId), "cancel quote", true);
        JsonNode cancellation = res == null ? null : res.path("cancellation");
        BigDecimal refund = cancellation == null ? null : decimal(cancellation.path("refundAmount"));
        return new CancelQuote(refund != null && refund.signum() >= 0 ? refund : null, message(res, null));
    }

    @Override
    public CancelResult cancel(String supplierBookingId) {
        JsonNode res = post("/oms/v3/hotel/cancel-booking", Map.of("bookingId", supplierBookingId, "remarks", "Cancelled by agency"), "cancel", true);
        if (explicitlyFailed(res)) {
            return new CancelResult(false, null, message(res, null));
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have cancelled, so this is unknown, not refused.
            throw new SupplierException("Tripjack hotel cancel returned an ambiguous response", null);
        }
        BigDecimal refund = decimal(res.path("totalRefundAmount"));
        return new CancelResult(true, refund != null && refund.signum() >= 0 ? refund : null, message(res, null));
    }

    /** The end of the last free slab (or the single deadline); null when the supplier gives none or it does not parse. */
    private static Instant freeUntil(JsonNode cancellation) {
        String deadline = cancellation.path("deadlineDateTime").asText("");
        for (JsonNode p : cancellation.path("penalties")) {
            if (p.path("amount").asDouble(-1) == 0 && !p.path("to").asText("").isBlank()) deadline = p.path("to").asText();
        }
        try {
            return deadline.isBlank() ? null : deadline.endsWith("Z") ? Instant.parse(deadline) : LocalDateTime.parse(deadline).atZone(HOTEL_ZONE).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> traveller(PaxDetail p) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ti", p.title());
        t.put("fN", p.firstName());
        t.put("lN", p.lastName());
        t.put("pt", p.type());
        return t;
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

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            String t = text(n);
            if (t != null && !t.isBlank()) return t;
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode n) {
        try {
            return n.isMissingNode() || n.isNull() ? null : new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code logBody=false} for calls whose error body may echo guest data. */
    private JsonNode post(String path, Object body, String what, boolean logBody) {
        ResolvedCredential credential = platformSupplierCredentialService.resolveActive(SupplierProvider.TRIPJACK);
        String host = credential.environment() == SupplierEnvironment.PROD ? prodBaseUrl : uatBaseUrl;
        RestClient client = restClientBuilder.clone().baseUrl(host).defaultHeader("apikey", credential.apiKey()).build();
        String raw;
        try {
            raw = client.post().uri(path).body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            String text = logBody ? e.getResponseBodyAsString() : "";
            log.error("Tripjack hotel {} HTTP {}: {}", what, e.getStatusCode().value(), text.length() > 200 ? text.substring(0, 200) : text);
            throw new SupplierException("Tripjack hotel " + what + " call failed (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new SupplierTimeoutException("Tripjack hotel " + what + " timed out", e);
            }
            throw new SupplierException("Tripjack hotel " + what + " call failed", e);
        } catch (RestClientException e) {
            throw new SupplierException("Tripjack hotel " + what + " call failed", e);
        }
        try {
            return raw == null || raw.isBlank() ? null : OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new SupplierException("Tripjack hotel " + what + " returned an unparseable response", e);
        }
    }
}
