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
    public FareReview review(List<String> offerIds, int adults, int children, int infants) {
        // a split round trip is one review of both price ids, so it is one supplier booking
        JsonNode res;
        try {
            res = post("/fms/v1/review", Map.of("priceIds", offerIds), "review");
        } catch (SupplierException e) {
            // Tripjack refuses a fare it can no longer sell with HTTP 400 (errCode 1000 "Requested flight is no longer available",
            // live 2026-10-30): that is an unavailable fare the agent can replace, not an unreachable supplier
            if (SupplierRefusal.of(e, log) == null) {
                throw e;
            }
            throw new FareUnavailableException(offerIds.size() > 1 ? AgencySupplierMessage.NOT_CONFIRMED_PAIR
                    : AgencySupplierMessage.NOT_CONFIRMED_FLIGHT + " - it may have sold out. Search again and pick another fare");
        }
        if (!succeeded(res)) {
            // the agency sees a fixed sentence; the supplier's own text stays in the log
            log.warn("Tripjack review refused offers {}: {}", offerIds, message(res, "no message"));
            throw new FareUnavailableException(offerIds.size() > 1 ? AgencySupplierMessage.NOT_CONFIRMED_PAIR
                    : AgencySupplierMessage.NOT_CONFIRMED_FLIGHT);
        }
        JsonNode fare = res.path("totalPriceInfo").path("totalFareDetail").path("fC");
        String reviewId = text(res.path("bookingId"));
        BigDecimal total = decimal(fare.path("TF"));
        if (reviewId == null || reviewId.isBlank() || total == null) {
            throw new SupplierException("Tripjack review response is missing the review id or total fare", null);
        }
        log.info("Tripjack review ok: priceIds={}, bookingId={}, total={}, base={}, conditions={}", offerIds, reviewId, total,
                decimal(fare.path("BF")), res.path("conditions").isMissingNode() ? "-" : res.path("conditions").toString());
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

        // shape only, never names, passport numbers, email or phone (DPDP): enough to see what Tripjack is objecting to
        log.info("Tripjack book request: bookingId={}, amount={}, travellers={}, email={}, phoneDigits={}", r.reviewId(), r.totalFare(),
                r.passengers().stream().map(p -> p.type() + "/" + p.title() + (p.dateOfBirth() != null ? "/dob" : "")
                        + (p.passportNumber() != null ? "/passport" : "") + (p.nationality() != null ? "/" + p.nationality() : "")).toList(),
                r.email() != null && !r.email().isBlank(), r.phone() == null ? 0 : r.phone().replaceAll("\\D", "").length());
        JsonNode res;
        try {
            res = post("/oms/v1/air/book", body, "book", false);
        } catch (SupplierException e) {
            String refusal = SupplierRefusal.of(e, log);
            if (refusal == null) {
                throw e; // timeout, 5xx or no answer: the supplier may have booked, so the caller treats it as unknown
            }
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), refusal, null);
        }
        if (explicitlyFailed(res)) {
            log.warn("Tripjack book refused (200): bookingId={}, errCode={}, message={}", r.reviewId(),
                    res.path("errors").path(0).path("errCode").asText(""), message(res, "Booking failed"));
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), message(res, "Booking failed"), null);
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have booked, so this is unknown, not failed.
            log.warn("Tripjack book ambiguous answer: bookingId={}, empty={}", r.reviewId(), res == null);
            throw new SupplierException("Tripjack book returned an ambiguous response", null);
        }
        // Accepted is not ticketed: the ticket arrives asynchronously, so the caller polls bookingDetails.
        log.info("Tripjack book accepted: bookingId={}, status={}", r.reviewId(), res.path("status").toString());
        return new BookResult(r.reviewId(), SupplierBookingStatus.PENDING, null, List.of(), null, null);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        JsonNode res = post("/oms/v1/booking-details", Map.of("bookingId", supplierBookingId), "booking-details");
        if (!succeeded(res)) {
            log.warn("Tripjack booking-details not successful: bookingId={}, message={}", supplierBookingId, message(res, "-"));
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), message(res, null), null);
        }
        String orderStatus = res.path("order").path("status").asText("").toUpperCase();
        SupplierBookingStatus status = switch (orderStatus) {
            case "SUCCESS", "TICKETED" -> SupplierBookingStatus.TICKETED;
            case "FAILED" -> SupplierBookingStatus.FAILED;
            case "CANCELLED" -> SupplierBookingStatus.CANCELLED; // how an accepted amendment is confirmed
            default -> SupplierBookingStatus.PENDING;
        };
        // pnrDetails maps each sector ("DEL-BOM") to its PNR; a split round trip can have one per direction, so keep every distinct one
        java.util.Set<String> pnrs = new java.util.LinkedHashSet<>();
        List<String> tickets = new ArrayList<>();
        List<TicketLine> lines = new ArrayList<>();
        int paxIndex = 0;
        for (JsonNode traveller : res.path("itemInfos").path("AIR").path("travellerInfos")) {
            java.util.Set<String> sectors = new java.util.LinkedHashSet<>();
            traveller.path("pnrDetails").fieldNames().forEachRemaining(sectors::add);
            traveller.path("ticketNumberDetails").fieldNames().forEachRemaining(sectors::add);
            for (String sector : sectors) {
                lines.add(new TicketLine(paxIndex, sector, blankToNull(traveller.path("pnrDetails").path(sector).asText(null)),
                        blankToNull(traveller.path("ticketNumberDetails").path(sector).asText(null))));
            }
            paxIndex++;
            traveller.path("pnrDetails").elements().forEachRemaining(v -> {
                if (!v.asText("").isBlank()) {
                    pnrs.add(v.asText().trim());
                }
            });
            traveller.path("ticketNumberDetails").fields().forEachRemaining(e -> {
                if (!e.getValue().asText("").isBlank()) {
                    tickets.add(e.getValue().asText());
                }
            });
        }
        // shortcut: the charged amount is not parsed (field unknown until the slice 0 spike), so no variance is detected here
        String pnr = pnrs.isEmpty() ? null : String.join(" / ", pnrs); // platform_booking.pnr is 40: room for several 6-character PNRs
        log.info("Tripjack booking-details: bookingId={}, orderStatus={}, mapped={}, pnr={}, tickets={}, amount={}", supplierBookingId,
                orderStatus, status, pnr, tickets.size(), res.path("order").path("amount").asText("-"));
        return new BookResult(supplierBookingId, status, pnr, tickets, orderStatus.isEmpty() ? null : orderStatus, null, lines);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Override
    public CancelQuote cancelQuote(String supplierBookingId) {
        JsonNode res = post("/oms/v1/air/amendment/amendment-charges", amendmentBody(supplierBookingId), "cancel quote");
        return new CancelQuote(refundOf(res), message(res, null));
    }

    @Override
    public CancelResult cancel(String supplierBookingId) {
        JsonNode res = post("/oms/v1/air/amendment/submit-amendment", amendmentBody(supplierBookingId), "cancel");
        if (explicitlyFailed(res)) {
            return new CancelResult(false, null, message(res, null));
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have cancelled, so this is unknown, not refused.
            throw new SupplierException("Tripjack cancel returned an ambiguous response", null);
        }
        return new CancelResult(true, refundOf(res), message(res, null));
    }

    private String fareRules(String reviewId) {
        try {
            JsonNode res = post("/fms/v2/farerule", Map.of("id", reviewId, "flowType", "REVIEW"), "farerule");
            if (res == null) {
                return null; // 200 with an empty body: the review goes on without fare rules
            }
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
        long started = System.currentTimeMillis();
        try {
            raw = client.post().uri(path).body(body).retrieve().body(String.class);
            log.info("Tripjack {} answered in {} ms", what, System.currentTimeMillis() - started);
        } catch (RestClientResponseException e) {
            String text = logBody ? e.getResponseBodyAsString() : "";
            log.error("Tripjack {} HTTP {} after {} ms: {}", what, e.getStatusCode().value(), System.currentTimeMillis() - started,
                    text.length() > 200 ? text.substring(0, 200) : text);
            throw new SupplierException("Tripjack " + what + " call failed (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            // a read timeout can arrive wrapped in a body-extraction error, not only as ResourceAccessException
            boolean timeout = false;
            for (Throwable c = e; c != null && !timeout; c = c.getCause()) {
                timeout = c instanceof SocketTimeoutException;
            }
            log.error("Tripjack {} {} after {} ms: {}", what, timeout ? "timed out" : "failed", System.currentTimeMillis() - started, e.getMessage());
            if (timeout) {
                throw new SupplierTimeoutException("Tripjack " + what + " timed out", e);
            }
            throw new SupplierException("Tripjack " + what + " call failed", e);
        }
        try {
            return raw == null ? null : OBJECT_MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new SupplierException("Tripjack " + what + " returned an unparseable response", e);
        }
    }
}
