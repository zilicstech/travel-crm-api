package com.voyra.crm.service.booking;

import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.MockHotelSearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import com.voyra.crm.util.ExcludeFromJacocoGenerated;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default hotel booking provider: fares come from {@link MockHotelSearchProvider} so a review prices a room exactly as
 * the detail page showed it, with the same switches as the flight mock. Free cancellation ends two days before
 * check-in; a cancel before then refunds the whole total, after it nothing. Ids carry what a refund needs (check-in
 * date, total) so a restart loses nothing.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.hotel-booking.provider", havingValue = "mock", matchIfMissing = true)
public class MockHotelBookingProvider implements HotelBookingProvider {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String REVIEW_PREFIX = "mock-hrv-";
    private static final String BOOKING_PREFIX = "mock-hbk-";

    private final HotelBookingProviderConfig config;

    /** Remaining PENDING answers per supplier booking id (in-memory: mock only). */
    private final Map<String, Integer> pendingLeft = new ConcurrentHashMap<>();

    @Override
    public FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut, List<HotelRoomRequest> occupancy) {
        int rooms = occupancy.size(); // the mock search prices per room, whoever is in it - so does the review
        var mock = config.getMock();
        if (mock.isFareExpired()) {
            throw new FareUnavailableException("The selected room is no longer available");
        }
        int nights = (int) Math.max(1, ChronoUnit.DAYS.between(checkIn, checkOut));
        BigDecimal total = MockHotelSearchProvider.optionTotal(hotelId, optionId, nights, rooms);
        if (total == null) {
            total = BigDecimal.valueOf((3000 + Math.floorMod(String.valueOf(optionId).hashCode(), 5000)) * (long) nights * Math.max(1, rooms)).setScale(2);
        }
        total = total.multiply(BigDecimal.ONE.add(mock.getRepricePct().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        LocalDate deadline = checkIn.minusDays(2);
        // a fresh id per review, like Tripjack: platform_booking.supplier_review_id is UNIQUE
        return new FareReview(REVIEW_PREFIX + checkIn.format(DAY) + "-" + UUID.randomUUID().toString().replace("-", ""),
                total, MockHotelSearchProvider.baseOf(total), "INR", "Mock rate rules: free cancellation until two days before check-in.",
                Instant.now().plus(15, ChronoUnit.MINUTES), null,
                "Free cancellation until " + deadline, deadline.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Override
    public BookResult book(BookRequest r) {
        String id = bookingId(r.reviewId(), r.totalFare());
        if (config.getMock().isBookFail()) {
            return new BookResult(id, SupplierBookingStatus.FAILED, null, List.of(), "Mock: room sold out", null);
        }
        if (config.getMock().isBookTimeout()) {
            throw new SupplierTimeoutException("Mock supplier timed out on book", null);
        }
        int pending = config.getMock().getPendingPolls();
        if (pending > 0) {
            pendingLeft.merge(id, pending, (a, c) -> a);
            return new BookResult(id, SupplierBookingStatus.PENDING, null, List.of(), "Confirmation in progress", null);
        }
        return confirmed(id);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        int[] left = {0};
        pendingLeft.computeIfPresent(supplierBookingId, (k, v) -> {
            left[0] = v;
            return v > 0 ? v - 1 : v;
        });
        if (left[0] > 0) {
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), "Confirmation in progress", null);
        }
        return confirmed(supplierBookingId);
    }

    @Override
    public CancelQuote cancelQuote(String supplierBookingId) {
        BigDecimal refund = refund(supplierBookingId);
        return new CancelQuote(refund, refund == null ? null : "Mock refund: " + (refund.signum() > 0 ? "full total, before the free-cancellation deadline" : "nothing, past the free-cancellation deadline"));
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

    /** {@code mock-hbk-<12 hex>-<check-in yyyyMMdd or none>-<total>}: short, stable per review, refund-ready. */
    static String bookingId(String reviewId, BigDecimal total) {
        byte[] digest = sha256().digest(reviewId.getBytes(StandardCharsets.UTF_8));
        String day = reviewId.startsWith(REVIEW_PREFIX) ? reviewId.substring(REVIEW_PREFIX.length(), REVIEW_PREFIX.length() + 8) : "none";
        return BOOKING_PREFIX + HexFormat.of().formatHex(digest).substring(0, 12) + "-" + day + "-" + total.toPlainString();
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

    /** Whole total before the deadline, 0 after; null for an id that carries no total or date (e.g. a review id after a timeout). */
    private static BigDecimal refund(String id) {
        if (!id.startsWith(BOOKING_PREFIX)) {
            return null;
        }
        String[] parts = id.split("-");
        try {
            LocalDate checkIn = LocalDate.parse(parts[3], DAY);
            BigDecimal total = new BigDecimal(parts[4]);
            return Instant.now().isBefore(checkIn.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()) ? total : BigDecimal.ZERO.setScale(2);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BookResult confirmed(String id) {
        String voucher = ("MH" + Integer.toString(Math.floorMod(id.hashCode(), 36 * 36 * 36 * 36), 36)).toUpperCase();
        String[] parts = id.split("-");
        BigDecimal total = id.startsWith(BOOKING_PREFIX) && parts.length > 4 ? parseOrNull(parts[4]) : null;
        return new BookResult(id, SupplierBookingStatus.TICKETED, voucher, List.of(), "Confirmed", total);
    }

    private static BigDecimal parseOrNull(String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
