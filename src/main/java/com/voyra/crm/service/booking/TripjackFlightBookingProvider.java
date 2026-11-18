package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightSeatMapResponse;
import com.voyra.crm.dto.SeatOptionResponse;
import com.voyra.crm.dto.SegmentSeatMapResponse;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.VoyraLegal;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tripjack flight booking, on the base URL and key of the supplier row each call is given.
 *
 * <p>Uses dedicated short-timeout builders for optional/interactive review calls ({@code /fms/v2/farerule} and
 * {@code /fms/v1/seat}) so a hanging fare-rule call never blocks a review for 60 seconds, while keeping the full
 * transactional timeout on {@code /fms/v1/review}, {@code /oms/v1/air/book} and {@code /oms/v1/booking-details}.
 */
@Service
@Slf4j
public class TripjackFlightBookingProvider implements FlightBookingProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    record ItinerarySeg(String key, String airlineCode, String flightNumber, String origin, String destination) {
    }

    private final RestClient.Builder restClientBuilder;
    private final RestClient.Builder fastRestClientBuilder;
    private final RestClient.Builder seatRestClientBuilder;
    private final PlatformSettingService settings;

    public TripjackFlightBookingProvider(@Qualifier("tripjackRestClientBuilder") RestClient.Builder restClientBuilder,
                                         @Qualifier("tripjackFastRestClientBuilder") RestClient.Builder fastRestClientBuilder,
                                         @Qualifier("tripjackSeatRestClientBuilder") RestClient.Builder seatRestClientBuilder,
                                         PlatformSettingService settings) {
        this.restClientBuilder = restClientBuilder;
        this.fastRestClientBuilder = fastRestClientBuilder;
        this.seatRestClientBuilder = seatRestClientBuilder;
        this.settings = settings;
    }

    @Override
    public SupplierProvider provider() {
        return SupplierProvider.TRIPJACK;
    }

    /** Tripjack accepts an amendment and cancels it later; the poller settles once booking-details says cancelled. */
    @Override
    public boolean cancelIsAsync() {
        return true;
    }

    @Override
    public FareReview review(List<String> offerIds, int adults, int children, int infants, ResolvedCredential supplier) {
        // a split round trip is one review of both price ids, so it is one supplier booking
        JsonNode res;
        try {
            res = post(supplier, "/fms/v1/review", Map.of("priceIds", offerIds), "review");
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
        JsonNode conditions = res.path("conditions");
        log.info("Tripjack review ok: priceIds={}, bookingId={}, total={}, base={}, conditions={}", offerIds, reviewId, total,
                decimal(fare.path("BF")), conditions.isMissingNode() ? "-" : conditions.toString());
        FlightCompliance fc = flightCompliance(conditions);
        if (fc.gstMandatory()) {
            VoyraLegal legal = settings.voyraLegal();
            if (legal == null || legal.gstin() == null || legal.legalName() == null) {
                throw new BookingConflictException("GST_NOT_CONFIGURED",
                        "This fare requires GST details, which are not configured on Voyra yet - pick another fare or contact Voyra", Map.of());
            }
        }
        long st = conditions.path("st").asLong(0);
        Instant expiresAt = st > 0 ? Instant.now().plusSeconds(st) : null;
        return new FareReview(reviewId, total, decimal(fare.path("BF")), "INR",
                fareRules(supplier, reviewId, "REVIEW"), expiresAt, res.path("tripInfos").isMissingNode() ? null : res.path("tripInfos"),
                null, null, fc.documentIdRequired(), HotelCompliance.NONE, fc);
    }

    /**
     * Parses Tripjack Air Review {@code conditions}: {@code isa} = seat map applicable, {@code ifsm || fsc.issi} = seat
     * selection mandatory (live 2026-11-18: IndiGo bundled fares set {@code fsc.issi=true} with ₹0 seats and fail book
     * with 8038 without seats, whereas unbundled Saver fares set {@code ifsm=false, fsc.issi=true} with only paid seats
     * and ticket without seats).
     */
    static FlightCompliance flightCompliance(JsonNode c) {
        boolean ifsm = c.path("ifsm").asBoolean(false);
        boolean issi = c.path("fsc").path("issi").asBoolean(false);
        return new FlightCompliance(
                c.path("isa").asBoolean(false),
                ifsm || issi,
                !ifsm && issi,
                c.path("dc").path("idm").asBoolean(false),
                c.path("ipa").asBoolean(false),
                c.path("pcs").path("pm").asBoolean(false),
                c.path("pcs").path("pped").asBoolean(false),
                c.path("dob").path("adobr").asBoolean(false),
                c.path("iecr").asBoolean(false),
                c.path("gst").path("igm").asBoolean(false));
    }

    @Override
    public FlightSeatMapResponse seatMap(String bookingId, String reviewId, Map<String, Object> itinerary,
                                         boolean seatMandatory, ResolvedCredential supplier) {
        JsonNode res;
        try {
            res = post(seatRestClientBuilder, supplier, "/fms/v1/seat", Map.of("bookingId", reviewId), "seat", true);
        } catch (SupplierException e) {
            if (SupplierRefusal.of(e, log) != null) {
                return FlightSeatMapResponse.builder().bookingId(bookingId).seatMandatory(false).segments(List.of()).build();
            }
            throw e;
        }
        if (!succeeded(res)) {
            log.warn("Tripjack seat map not applicable for {}: {}", reviewId, message(res, "no message"));
            return FlightSeatMapResponse.builder().bookingId(bookingId).seatMandatory(false).segments(List.of()).build();
        }
        JsonNode tripSeat = res.path("tripSeatMap").path("tripSeat");
        Map<String, ItinerarySeg> metaByKey = new LinkedHashMap<>();
        segmentsOf(itinerary).forEach(s -> metaByKey.put(s.key(), s));
        tripSeat.fieldNames().forEachRemaining(k -> metaByKey.putIfAbsent(k, new ItinerarySeg(k, null, null, null, null)));
        List<SegmentSeatMapResponse> segments = new ArrayList<>();
        for (ItinerarySeg seg : metaByKey.values()) {
            JsonNode node = tripSeat.path(seg.key());
            if (!node.isMissingNode() && !node.isNull()) {
                segments.add(parseSegmentSeats(seg, node));
            }
        }
        boolean anyAvailable = segments.stream().anyMatch(s -> s.getSeats().stream().anyMatch(o -> !o.isBooked()));
        return FlightSeatMapResponse.builder()
                .bookingId(bookingId)
                .seatMandatory(seatMandatory && anyAvailable)
                .segments(segments)
                .build();
    }

    static List<ItinerarySeg> segmentsOf(Map<String, Object> itinerary) {
        if (itinerary == null) {
            return List.of();
        }
        List<ItinerarySeg> out = new ArrayList<>();
        for (JsonNode sI : OBJECT_MAPPER.valueToTree(itinerary).findValues("sI")) {
            for (JsonNode seg : sI) {
                String origin = blankToNull(text(seg.path("da").path("code")));
                String destination = blankToNull(text(seg.path("aa").path("code")));
                String id = blankToNull(text(seg.path("id")));
                String key = id != null ? id : seg.path("da").path("code").asText("").trim() + "-" + seg.path("aa").path("code").asText("").trim();
                out.add(new ItinerarySeg(key, blankToNull(text(seg.path("fD").path("aI").path("code"))),
                        blankToNull(text(seg.path("fD").path("fN"))), origin, destination));
            }
        }
        return out;
    }

    private static SegmentSeatMapResponse parseSegmentSeats(ItinerarySeg seg, JsonNode node) {
        int maxRow = node.path("sData").path("row").asInt(0);
        int maxCol = node.path("sData").path("column").asInt(0);
        List<SeatOptionResponse> seats = new ArrayList<>();
        for (JsonNode s : node.path("sInfo")) {
            String code = blankToNull(text(s.path("code")));
            if (code == null) {
                continue;
            }
            String seatNo = blankToNull(text(s.path("seatNo")));
            int row = s.path("seatPosition").path("row").asInt(0);
            int col = s.path("seatPosition").path("column").asInt(0);
            maxRow = Math.max(maxRow, row);
            maxCol = Math.max(maxCol, col);
            BigDecimal rawAmount = decimal(s.path("amount"));
            BigDecimal amount = (rawAmount == null || rawAmount.signum() < 0 ? BigDecimal.ZERO : rawAmount)
                    .setScale(2, RoundingMode.HALF_UP);
            seats.add(SeatOptionResponse.builder()
                    .code(code)
                    .seatNo(seatNo != null ? seatNo : code)
                    .row(row)
                    .column(col)
                    .booked(s.path("isBooked").asBoolean(false))
                    .amount(amount)
                    .legRoom(s.path("isLegRoom").asBoolean(false))
                    .aisle(s.path("isAisle").asBoolean(false))
                    .exitRow(s.path("isExitRow").asBoolean(false))
                    .build());
        }
        return SegmentSeatMapResponse.builder()
                .segmentKey(seg.key())
                .airlineCode(seg.airlineCode())
                .flightNumber(seg.flightNumber())
                .origin(seg.origin())
                .destination(seg.destination())
                .rows(maxRow)
                .columns(maxCol)
                .note(blankToNull(text(node.path("nt"))))
                .seats(seats)
                .build();
    }

    @Override
    public BookResult book(BookRequest r, ResolvedCredential supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", r.reviewId());
        body.put("paymentInfos", List.of(Map.of("amount", r.totalFare())));
        body.put("travellerInfo", r.passengers().stream().map(TripjackFlightBookingProvider::traveller).toList());
        List<String> emails = r.email() == null ? List.of() : List.of(r.email());
        List<String> contacts = r.phone() == null ? List.of() : List.of(r.phone());
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("emails", emails);
        delivery.put("contacts", contacts);
        body.put("deliveryInfo", delivery);
        if (r.flightCompliance().emergencyContactRequired()) {
            Map<String, Object> contact = new LinkedHashMap<>();
            contact.put("emails", emails);
            contact.put("contacts", contacts);
            String ecn = r.passengers().isEmpty() ? "Emergency Contact"
                    : (r.passengers().get(0).firstName() + " " + r.passengers().get(0).lastName()).trim();
            contact.put("ecn", ecn);
            body.put("contactInfo", contact);
        }
        Map<String, Object> gst = gstInfo(r);
        if (gst != null) {
            body.put("gstInfo", gst);
        }

        // shape only, never names, passport or ID numbers, email or phone (DPDP): enough to see what Tripjack is objecting to
        log.info("Tripjack book request: bookingId={}, amount={}, travellers={}, email={}, phoneDigits={}", r.reviewId(), r.totalFare(),
                r.passengers().stream().map(p -> p.type() + "/" + normalizeTitle(p.type(), p.title())
                        + (p.dateOfBirth() != null ? "/dob" : "")
                        + (p.passportNumber() != null ? "/passport" : "") + (p.nationality() != null ? "/" + p.nationality() : "")
                        + (p.documentId() != null ? "/id" : "") + (p.pan() != null ? "/pan" : "")
                        + (p.seats() != null && !p.seats().isEmpty() ? "/seats=" + p.seats().size() : "")).toList(),
                r.email() != null && !r.email().isBlank(), r.phone() == null ? 0 : r.phone().replaceAll("\\D", "").length());
        JsonNode res;
        try {
            res = post(restClientBuilder, supplier, "/oms/v1/air/book", body, "book", false);
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

    private Map<String, Object> gstInfo(BookRequest r) {
        if (!r.flightCompliance().gstMandatory()) {
            return null;
        }
        VoyraLegal legal = settings.voyraLegal();
        if (legal == null || legal.gstin() == null || legal.legalName() == null) {
            log.warn("Tripjack flight {} asks for GST details but Voyra's GSTIN or legal name is not set; booking without them", r.reviewId());
            return null;
        }
        Map<String, Object> gst = new LinkedHashMap<>();
        gst.put("gstNumber", legal.gstin());
        gst.put("registeredName", legal.legalName().length() > 35 ? legal.legalName().substring(0, 35) : legal.legalName());
        putIfPresent(gst, "email", r.email());
        String digits = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            gst.put("mobile", digits);
        }
        if (legal.address() != null) {
            gst.put("address", legal.address().length() > 70 ? legal.address().substring(0, 70) : legal.address());
        }
        return gst;
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId, ResolvedCredential supplier) {
        JsonNode res = post(supplier, "/oms/v1/booking-details", Map.of("bookingId", supplierBookingId), "booking-details");
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
        String pnr = pnrs.isEmpty() ? null : String.join(" / ", pnrs); // platform_booking.pnr is 40: room for several 6-character PNRs
        log.info("Tripjack booking-details: bookingId={}, orderStatus={}, mapped={}, pnr={}, tickets={}, amount={}", supplierBookingId,
                orderStatus, status, pnr, tickets.size(), res.path("order").path("amount").asText("-"));
        return new BookResult(supplierBookingId, status, pnr, tickets, orderStatus.isEmpty() ? null : orderStatus, null, lines);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Override
    public String searchFareRules(String priceId, ResolvedCredential supplier) {
        return fareRules(supplier, priceId, "SEARCH");
    }

    private String fareRules(ResolvedCredential supplier, String id, String flowType) {
        try {
            JsonNode res = post(fastRestClientBuilder, supplier, "/fms/v2/farerule", Map.of("id", id, "flowType", flowType), "farerule", true);
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

    /** Tripjack Air API: Adult allows Mr/Mrs/Ms; Child and Infant allow Ms/Master. */
    static String normalizeTitle(String type, String title) {
        String clean = title == null ? "" : title.trim().replace(".", "").toUpperCase(Locale.ROOT);
        if ("CHILD".equals(type) || "INFANT".equals(type)) {
            return ("MS".equals(clean) || "MISS".equals(clean) || "MRS".equals(clean)) ? "Ms" : "Master";
        }
        if ("MRS".equals(clean)) {
            return "Mrs";
        }
        return ("MS".equals(clean) || "MISS".equals(clean)) ? "Ms" : "Mr";
    }

    private static Map<String, Object> traveller(PaxDetail p) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ti", normalizeTitle(p.type(), p.title()));
        t.put("fN", p.firstName());
        t.put("lN", p.lastName());
        t.put("pt", p.type());
        putIfPresent(t, "dob", p.dateOfBirth());
        putIfPresent(t, "pNum", p.passportNumber());
        putIfPresent(t, "eD", p.passportExpiry());
        putIfPresent(t, "pNat", p.nationality());
        putIfPresent(t, "di", p.documentId());
        putIfPresent(t, "pan", p.pan());
        if (!"INFANT".equals(p.type()) && p.seats() != null && !p.seats().isEmpty()) {
            t.put("ssrSeatInfos", p.seats().stream()
                    .map(s -> Map.of("key", s.segmentKey(), "code", s.code()))
                    .toList());
        }
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

    private JsonNode post(ResolvedCredential supplier, String path, Object body, String what) {
        return post(restClientBuilder, supplier, path, body, what, true);
    }

    /** {@code logBody=false} for calls whose error body may echo passenger data. */
    private JsonNode post(RestClient.Builder builder, ResolvedCredential supplier, String path, Object body, String what, boolean logBody) {
        RestClient client = builder.clone()
                .baseUrl(supplier.baseUrl())
                .defaultHeader("apikey", supplier.apiKey())
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
