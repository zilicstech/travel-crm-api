package com.voyra.crm.service.booking;

import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    private final FlightBookingProviderConfig config;

    /** Remaining PENDING answers per supplier booking id (in-memory: mock only). The fare lives in the id itself. */
    private final Map<String, Integer> pendingLeft = new ConcurrentHashMap<>();

    @Override
    public FareReview review(String offerId, int adults, int children, int infants) {
        var mock = config.getMock();
        if (mock.isFareExpired()) {
            throw new FareUnavailableException("The selected fare is no longer available");
        }
        BigDecimal perAdult = BigDecimal.valueOf(3000 + Math.floorMod(offerId.hashCode(), 5000));
        BigDecimal total = perAdult.multiply(BigDecimal.valueOf(adults))
                .add(perAdult.multiply(new BigDecimal("0.75")).multiply(BigDecimal.valueOf(children)))
                .add(perAdult.multiply(new BigDecimal("0.10")).multiply(BigDecimal.valueOf(infants)));
        total = total.multiply(BigDecimal.ONE.add(mock.getRepricePct().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = total.multiply(BASE_SHARE).setScale(2, RoundingMode.HALF_UP);
        // a fresh id per review, like Tripjack: platform_booking.supplier_review_id is UNIQUE
        return new FareReview("mock-rv-" + offerId + "-" + UUID.randomUUID().toString().replace("-", ""), total, base, "INR", "Mock fare rules: refundable, 80% back.",
                Instant.now().plus(15, ChronoUnit.MINUTES), null);
    }

    @Override
    public BookResult book(BookRequest r) {
        String id = "mock-bk-" + r.reviewId() + "-" + r.totalFare().toPlainString();
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
        return new CancelResult(true, refund(supplierBookingId), "Mock cancellation accepted");
    }

    private BigDecimal refund(String id) {
        return fare(id).multiply(REFUND_SHARE).setScale(2, RoundingMode.HALF_UP);
    }

    /** The booked fare, carried in the booking id so it survives a restart. */
    private static BigDecimal fare(String id) {
        try {
            return new BigDecimal(id.substring(id.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private BookResult ticketed(String id) {
        String tag = Integer.toString(Math.floorMod(id.hashCode(), 1_000_000));
        String pnr = ("MK" + Integer.toString(Math.floorMod(id.hashCode(), 36 * 36 * 36 * 36), 36)).toUpperCase();
        return new BookResult(id, SupplierBookingStatus.TICKETED, pnr, List.of("MOCK-TKT-" + tag), "Ticketed", fare(id));
    }
}
