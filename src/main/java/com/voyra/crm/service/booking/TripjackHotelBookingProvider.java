package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.VoyraLegal;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider;
import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider.OptionRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tripjack hotel booking (Hotel API v3) with the key of the supplier row each call is given. Review runs on the search
 * host ({@code app.hotel-supplier.tripjack.*-base-url}); book, booking-details and cancel run on the separate booker
 * host ({@code app.hotel-supplier.tripjack.*-booker-url}), per the v3 documentation.
 *
 * <p>Written to Tripjack's v3 documentation; no live hotel booking has been captured yet. Rulings shared with the
 * flight provider: FAILED only on an explicit {@code status.success=false}; an empty or odd body is a
 * {@link SupplierException}; a status not in the documented list is PENDING; an unknown refund is null; book error
 * bodies are never logged (guest PII), others are cut to 200 characters. The cancel quote reads booking-details and
 * works the refund out of the booking's penalty slabs; it never calls cancel-booking, which has no dry run.
 */
@Service
@Slf4j
public class TripjackHotelBookingProvider implements HotelBookingProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Tripjack's cancellation and penalty times are India time (GMT+5:30) with no zone in the string. */
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Kolkata");
    /** Tripjack's titles; the booking form's short "Mstr" is its "Master". */
    private static final Map<String, String> TITLES = Map.of("MR", "Mr", "MRS", "Mrs", "MS", "Ms", "MISS", "Miss", "MSTR", "Master", "MASTER", "Master");

    private final RestClient.Builder restClientBuilder;
    private final PlatformSettingService settings;
    private final String uatBaseUrl;
    private final String prodBaseUrl;
    private final String uatBookerUrl;
    private final String prodBookerUrl;

    public TripjackHotelBookingProvider(@Qualifier("tripjackRestClientBuilder") RestClient.Builder restClientBuilder,
                                        PlatformSettingService settings,
                                        @Value("${app.hotel-supplier.tripjack.uat-base-url:https://apitest-hms.tripjack.com}") String uatBaseUrl,
                                        @Value("${app.hotel-supplier.tripjack.prod-base-url:https://hms-search.tripjack.com}") String prodBaseUrl,
                                        @Value("${app.hotel-supplier.tripjack.uat-booker-url:https://apitest-hotel-booker.tripjack.com}") String uatBookerUrl,
                                        @Value("${app.hotel-supplier.tripjack.prod-booker-url:https://hms-booker.tripjack.com}") String prodBookerUrl) {
        this.restClientBuilder = restClientBuilder;
        this.settings = settings;
        this.uatBaseUrl = uatBaseUrl;
        this.prodBaseUrl = prodBaseUrl;
        this.uatBookerUrl = uatBookerUrl;
        this.prodBookerUrl = prodBookerUrl;
    }

    @Override
    public SupplierProvider provider() {
        return SupplierProvider.TRIPJACK;
    }

    /** Same as flights: an accepted cancel is confirmed by booking-details later, through the poller. */
    @Override
    public boolean cancelIsAsync() {
        return true;
    }

    /**
     * {@code POST /hms/v3/hotel/review} with the option, the pricing call's review hash and the search's correlation id
     * (all three carried in the option id, see {@link OptionRef}). The stay and rooms are not sent: Tripjack reviews the
     * option as priced.
     */
    @Override
    public FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut, List<HotelRoomRequest> rooms,
                             ResolvedCredential supplier) {
        OptionRef ref = OptionRef.parse(optionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correlationId", ref.correlationId());
        body.put("optionId", ref.optionId());
        body.put("reviewHash", ref.reviewHash());
        body.put("hid", hotelId);
        JsonNode res = post(searchHost(supplier), supplier, "/hms/v3/hotel/review", body, "review", true);
        if (!succeeded(res)) {
            log.warn("Tripjack hotel review refused option {}: {}", ref.optionId(), message(res, "no message"));
            throw new FareUnavailableException(AgencySupplierMessage.NOT_CONFIRMED_HOTEL);
        }
        JsonNode option = res.path("option");
        JsonNode pricing = option.path("pricing");
        String reviewId = text(res.path("bookingId"));
        BigDecimal total = decimal(pricing.path("totalPrice"));
        if (reviewId == null || reviewId.isBlank() || total == null) {
            throw new SupplierException("Tripjack hotel review response is missing the booking id or total", null);
        }
        JsonNode cancellation = option.path("cancellation");
        boolean refundable = cancellation.path("isRefundable").asBoolean(false);
        String notes = text(option.path("bookingNotes"));
        return new FareReview(reviewId, total, TripjackHotelSearchProvider.baseRate(pricing), pricing.path("currency").asText("INR"),
                notes == null || notes.isBlank() ? null : notes, null, option,
                TripjackHotelSearchProvider.cancellationPolicy(cancellation, refundable), refundable ? freeUntil(cancellation) : null,
                false, compliance(res, option));
    }

    /**
     * {@code option.compliance} of the review (the Book docs also name the flags {@code ipr} / {@code ipm}, so those count
     * too); a RESELLER or PASSTHROUGH GST type means Voyra's GST details go with the booking.
     */
    static HotelCompliance compliance(JsonNode res, JsonNode option) {
        JsonNode c = option.path("compliance");
        String gstType = c.path("gstType").asText("NA").toUpperCase(Locale.ROOT);
        return new HotelCompliance(c.path("panRequired").asBoolean(false) || res.path("ipr").asBoolean(false),
                c.path("passportRequired").asBoolean(false) || res.path("ipm").asBoolean(false),
                gstType.equals("RESELLER") || gstType.equals("PASSTHROUGH"));
    }

    @Override
    public BookResult book(BookRequest r, ResolvedCredential supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", r.reviewId());
        body.put("type", "HOTEL");
        body.put("roomTravellerInfo", roomTravellerInfo(r));
        body.put("deliveryInfo", deliveryInfo(r));
        Map<String, Object> gst = gstInfo(r);
        if (gst != null) {
            body.put("gstInfo", gst);
        }
        body.put("paymentInfos", List.of(Map.of("amount", r.totalFare())));

        JsonNode res;
        try {
            res = post(bookerHost(supplier), supplier, "/oms/v3/hotel/book", body, "book", false);
        } catch (SupplierException e) {
            String refusal = SupplierRefusal.of(e, log);
            if (refusal == null) {
                throw e; // timeout, 5xx or no answer: the supplier may have booked, so the caller treats it as unknown
            }
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), refusal, null);
        }
        if (explicitlyFailed(res)) {
            return new BookResult(r.reviewId(), SupplierBookingStatus.FAILED, null, List.of(), message(res, "Booking failed"), null);
        }
        if (!succeeded(res)) {
            // Empty or unexpected answer: the supplier may have booked, so this is unknown, not failed.
            throw new SupplierException("Tripjack hotel book returned an ambiguous response", null);
        }
        // Accepted is not confirmed (Tripjack confirms within 180 seconds): the caller polls bookingDetails.
        String bookingId = text(res.path("bookingId"));
        return new BookResult(bookingId == null || bookingId.isBlank() ? r.reviewId() : bookingId,
                SupplierBookingStatus.PENDING, null, List.of(), null, null);
    }

    /** Voyra's GSTIN and legal name for a RESELLER / PASSTHROUGH rate; null when not needed or not set (warned). */
    private Map<String, Object> gstInfo(BookRequest r) {
        if (r.compliance() == null || !r.compliance().gstRequired()) {
            return null;
        }
        VoyraLegal legal = settings.voyraLegal();
        if (legal.gstin() == null || legal.legalName() == null) {
            log.warn("Tripjack hotel {} asks for GST details but Voyra's GSTIN or legal name is not set; booking without them", r.reviewId());
            return null;
        }
        Map<String, Object> gst = new LinkedHashMap<>();
        gst.put("gstNumber", legal.gstin());
        gst.put("registeredName", legal.legalName().length() > 35 ? legal.legalName().substring(0, 35) : legal.legalName());
        return gst;
    }

    /** The contact phone split as Tripjack wants it: the number in {@code contacts}, its dialling code in {@code code}. */
    static Map<String, Object> deliveryInfo(BookRequest r) {
        String dial = r.dialCode() == null || r.dialCode().isBlank() ? "91" : r.dialCode();
        String phone = r.phone() == null ? "" : r.phone().trim();
        String digits = phone.replaceAll("\\D", "");
        if (phone.startsWith("+") && digits.startsWith(dial)) {
            digits = digits.substring(dial.length());
        }
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("emails", r.email() == null ? List.of() : List.of(r.email()));
        delivery.put("contacts", digits.isEmpty() ? List.of() : List.of(digits));
        delivery.put("code", digits.isEmpty() ? List.of() : List.of("+" + dial));
        return delivery;
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId, ResolvedCredential supplier) {
        JsonNode res = details(supplierBookingId, supplier, "booking-details");
        if (!succeeded(res)) {
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), message(res, null), null);
        }
        String orderStatus = res.path("order").path("status").asText("").toUpperCase(Locale.ROOT);
        SupplierBookingStatus status = switch (orderStatus) {
            case "SUCCESS" -> SupplierBookingStatus.TICKETED;
            case "FAILED", "ABORTED" -> SupplierBookingStatus.FAILED;
            case "CANCELLED" -> SupplierBookingStatus.CANCELLED; // how an accepted cancellation is confirmed
            // IN_PROGRESS, PAYMENT_SUCCESS, PAYMENT_PENDING, PENDING, CANCELLATION_PENDING (Tripjack cancels offline)
            default -> SupplierBookingStatus.PENDING;
        };
        String confirmation = text(res.path("hotelConfirmationNumber"));
        return new BookResult(supplierBookingId, status, confirmation == null || confirmation.isBlank() ? null : confirmation,
                List.of(), orderStatus.isEmpty() ? null : orderStatus, decimal(res.path("order").path("amount")));
    }

    private JsonNode details(String supplierBookingId, ResolvedCredential supplier, String what) {
        return post(bookerHost(supplier), supplier, "/oms/v3/hotel/booking-details", Map.of("bookingId", supplierBookingId), what, true);
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

    /**
     * One entry per room, each listing every guest (Tripjack wants one per adult and child). The lead PAN goes on the
     * first room's lead, or on every guest when the hotel requires PAN; a passport number goes with each guest who has one.
     */
    private static List<Map<String, Object>> roomTravellerInfo(BookRequest r) {
        List<List<PaxDetail>> rooms = r.rooms() == null || r.rooms().isEmpty() ? List.of(r.passengers()) : r.rooms();
        boolean panOnAll = r.compliance() != null && r.compliance().panRequired();
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<PaxDetail> room : rooms) {
            List<Map<String, Object>> travellers = room.stream().map(TripjackHotelBookingProvider::traveller).toList();
            if (r.leadPan() != null) {
                for (int i = 0; i < travellers.size(); i++) {
                    if (panOnAll || (out.isEmpty() && i == 0)) travellers.get(i).put("pan", r.leadPan());
                }
            }
            out.add(Map.of("travellerInfo", travellers));
        }
        return out;
    }

    private static Map<String, Object> traveller(PaxDetail p) {
        Map<String, Object> t = new LinkedHashMap<>();
        String title = p.title() == null ? "" : p.title().trim();
        t.put("ti", TITLES.getOrDefault(title.toUpperCase(Locale.ROOT), title));
        t.put("pt", "ADULT".equals(p.type()) ? "ADULT" : "CHILD");
        t.put("fN", p.firstName());
        t.put("lN", p.lastName());
        if (p.passportNumber() != null && !p.passportNumber().isBlank()) {
            t.put("pNum", p.passportNumber());
        }
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

    /** The v3 error envelope's {@code error.message}, else an {@code errors[0].message}; else the fallback. */
    private static String message(JsonNode res, String fallback) {
        if (res == null) {
            return fallback;
        }
        String m = res.path("error").path("message").asText(res.path("errors").path(0).path("message").asText(""));
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

    private String searchHost(ResolvedCredential supplier) {
        return supplier.environment() == SupplierEnvironment.PROD ? prodBaseUrl : uatBaseUrl;
    }

    private String bookerHost(ResolvedCredential supplier) {
        return supplier.environment() == SupplierEnvironment.PROD ? prodBookerUrl : uatBookerUrl;
    }

    /** {@code logBody=false} for calls whose error body may echo guest data; a null body posts nothing. */
    private JsonNode post(String host, ResolvedCredential supplier, String path, Object body, String what, boolean logBody) {
        RestClient client = restClientBuilder.clone().baseUrl(host).defaultHeader("apikey", supplier.apiKey()).build();
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
