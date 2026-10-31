package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.voyra.crm.service.MockFlightSearchProvider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import com.voyra.crm.util.ExcludeFromJacocoGenerated;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default booking provider: deterministic ids and fares derived from the offer id, with
 * switches ({@link FlightBookingProviderConfig.Mock}) so regressions can drive reprice,
 * expiry, slow ticketing and book timeouts with no supplier account.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.flight-booking.provider", havingValue = "mock", matchIfMissing = true)
public class MockFlightBookingProvider implements FlightBookingProvider {

    private static final BigDecimal REFUND_SHARE = new BigDecimal("0.80");
    private static final BigDecimal BASE_SHARE = new BigDecimal("0.85");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FlightBookingProviderConfig config;

    /** Remaining PENDING answers per supplier booking id (in-memory: mock only). The fare lives in the id itself. */
    private final Map<String, Integer> pendingLeft = new ConcurrentHashMap<>();

    @Override
    public FareReview review(FlightReviewRequest r) {
        FareReview base = review(FlightBookingProvider.offerIds(r), r.getAdults(), r.getChildren(), r.getInfants());
        // always a complete itinerary, like Tripjack (mirror, purge and the age check read it); shape mirrors its sI segments.
        // shortcut: a request without the display hints gets DEL-BOM 30 days out - mock data only, never a real supplier
        String date = blank(r.getDepartDate()) ? java.time.LocalDate.now().plusDays(30).toString() : r.getDepartDate();
        String origin = blank(r.getOrigin()) ? "DEL" : r.getOrigin();
        String destination = blank(r.getDestination()) ? "BOM" : r.getDestination();
        ObjectNode itinerary = JSON.createObjectNode();
        var sI = itinerary.putArray("sI");
        sI.add(segment(r.getAirline(), r.getAirlineCode(), r.getFlightNumber(), origin, destination, date,
                r.getDepartTime(), r.getArriveTime(), "06:00", "08:10"));
        if (!blank(r.getReturnOfferId())) {
            // the way back: the same route swapped, on the return hints (3 days later when they are missing)
            String back = blank(r.getReturnDepartDate()) ? java.time.LocalDate.parse(date).plusDays(3).toString() : r.getReturnDepartDate();
            ObjectNode returnSeg = segment(blank(r.getReturnAirline()) ? r.getAirline() : r.getReturnAirline(),
                    blank(r.getReturnAirlineCode()) ? r.getAirlineCode() : r.getReturnAirlineCode(),
                    blank(r.getReturnFlightNumber()) ? "102" : r.getReturnFlightNumber(), destination, origin, back,
                    r.getReturnDepartTime(), r.getReturnArriveTime(), "18:00", "20:10");
            returnSeg.put("isRs", true); // Tripjack's marker for the way back in one segment list; the ticket splits directions on it
            sI.add(returnSeg);
        }
        // the fare facts the ticket prints (a mock has no tax breakdown, so the ticket shows one tax line)
        var fd = itinerary.putArray("totalPriceList").addObject().put("fareIdentifier", "PUBLISHED").putObject("fd").putObject("ADULT");
        fd.put("cc", "ECONOMY").put("rT", 1).putObject("bI").put("iB", "15 Kg").put("cB", "7 Kg");
        return new FareReview(base.reviewId(), base.totalFare(), base.baseFare(), base.currency(), base.fareRules(), base.expiresAt(), itinerary);
    }

    private static ObjectNode segment(String airline, String airlineCode, String flightNumber, String origin, String destination,
                                      String date, String departTime, String arriveTime, String defaultDepart, String defaultArrive) {
        ObjectNode seg = JSON.createObjectNode();
        ObjectNode fD = seg.putObject("fD");
        fD.put("fN", blank(flightNumber) ? "101" : flightNumber);
        fD.putObject("aI").put("code", blank(airlineCode) ? "MK" : airlineCode)
                .put("name", blank(airline) ? "Mock Air" : airline);
        seg.put("duration", 130);
        seg.putObject("da").put("code", origin);
        seg.putObject("aa").put("code", destination);
        seg.put("dt", date + "T" + (blank(departTime) ? defaultDepart : departTime));
        seg.put("at", date + "T" + (blank(arriveTime) ? defaultArrive : arriveTime));
        return seg;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public FareReview review(List<String> offerIds, int adults, int children, int infants) {
        var mock = config.getMock();
        if (mock.isFareExpired()) {
            throw new FareUnavailableException("The selected fare is no longer available");
        }
        // a mock search offer is priced exactly as the search showed it (a round trip is the sum of both ways); any other id gets a fare from its hash
        BigDecimal total = BigDecimal.ZERO;
        for (String offerId : offerIds) {
            BigDecimal perAdult = MockFlightSearchProvider.adultFare(offerId);
            if (perAdult == null) {
                perAdult = BigDecimal.valueOf(3000 + Math.floorMod(offerId.hashCode(), 5000));
            }
            total = total.add(MockFlightSearchProvider.partyFare(perAdult, adults, children, infants));
        }
        String offerId = offerIds.get(0); // the review id names the onward offer only, so it stays under supplier_review_id's 120
        total = total.multiply(BigDecimal.ONE.add(mock.getRepricePct().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = total.multiply(BASE_SHARE).setScale(2, RoundingMode.HALF_UP);
        // a fresh id per review, like Tripjack: platform_booking.supplier_review_id is UNIQUE
        return new FareReview("mock-rv-" + offerId + "-" + UUID.randomUUID().toString().replace("-", ""), total, base, "INR", "Mock fare rules: refundable, 80% back.",
                Instant.now().plus(15, ChronoUnit.MINUTES), null);
    }

    @Override
    public BookResult book(BookRequest r) {
        String id = bookingId(r.reviewId(), r.totalFare());
        if (config.getMock().isBookFail()) {
            return new BookResult(id, SupplierBookingStatus.FAILED, null, List.of(), "Mock: fare sold out", null);
        }
        if (config.getMock().isBookTimeout()) {
            throw new SupplierTimeoutException("Mock supplier timed out on book", null);
        }
        int pending = config.getMock().getPendingPolls();
        if (pending > 0) {
            pendingLeft.merge(id, pending, (a, c) -> a);
            return new BookResult(id, SupplierBookingStatus.PENDING, null, List.of(), "Ticketing in progress", null);
        }
        return ticketed(id);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        int[] left = {0};
        pendingLeft.computeIfPresent(supplierBookingId, (k, v) -> {
            left[0] = v;
            return v > 0 ? v - 1 : v;
        });
        if (left[0] > 0) {
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), "Ticketing in progress", null);
        }
        return ticketed(supplierBookingId);
    }

    @Override
    public CancelQuote cancelQuote(String supplierBookingId) {
        return new CancelQuote(refund(supplierBookingId), "Mock refund: 80% of the fare");
    }

    @Override
    public CancelResult cancel(String supplierBookingId) {
        if (config.getMock().isCancelTimeout()) {
            throw new SupplierTimeoutException("Mock supplier timed out on cancel", null);
        }
        if (config.getMock().isCancelFail()) {
            return new CancelResult(false, null, "Mock: cancellation not allowed");
        }
        return new CancelResult(true, refund(supplierBookingId), "Mock cancellation accepted");
    }

    /** Short and stable per review, ending in the fare so a refund survives a restart: {@code mock-bk-<12 hex>-<fare>}. */
    static String bookingId(String reviewId, BigDecimal fare) {
        byte[] digest = sha256().digest(reviewId.getBytes(StandardCharsets.UTF_8));
        return "mock-bk-" + HexFormat.of().formatHex(digest).substring(0, 12) + "-" + fare.toPlainString();
    }

    /** Every JRE ships SHA-256, so the catch the compiler forces cannot run. */
    @ExcludeFromJacocoGenerated
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private BigDecimal refund(String id) {
        BigDecimal fare = fare(id);
        return (fare != null ? fare : BigDecimal.ZERO).multiply(REFUND_SHARE).setScale(2, RoundingMode.HALF_UP);
    }

    /** The booked fare, carried in a mock-bk id so it survives a restart; null for any other id (e.g. a review id after a timeout). */
    private static BigDecimal fare(String id) {
        if (!id.startsWith("mock-bk-")) {
            return null;
        }
        try {
            return new BigDecimal(id.substring(id.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BookResult ticketed(String id) {
        String tag = Integer.toString(Math.floorMod(id.hashCode(), 1_000_000));
        String pnr = ("MK" + Integer.toString(Math.floorMod(id.hashCode(), 36 * 36 * 36 * 36), 36)).toUpperCase();
        return new BookResult(id, SupplierBookingStatus.TICKETED, pnr, List.of("MOCK-TKT-" + tag), "Ticketed", fare(id));
    }
}
