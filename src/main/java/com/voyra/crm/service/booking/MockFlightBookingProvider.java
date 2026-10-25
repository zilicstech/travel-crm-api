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

    /** Remaining PENDING answers and the booked fare, per supplier booking id (in-memory: mock only). */
    private final Map<String, Integer> pendingLeft = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> fares = new ConcurrentHashMap<>();

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
        return new FareReview("mock-rv-" + offerId, total, base, "INR", "Mock fare rules: refundable, 80% back.",
                Instant.now().plus(15, ChronoUnit.MINUTES), null);
    }

    @Override
    public BookResult book(BookRequest r) {
        String id = "mock-bk-" + r.reviewId();
        fares.put(id, r.totalFare());
        if (config.getMock().isBookTimeout()) {
            throw new SupplierTimeoutException("Mock supplier timed out on book", null);
        }
        int pending = config.getMock().getPendingPolls();
        if (pending > 0) {
            pendingLeft.put(id, pending);
            return new BookResult(id, SupplierBookingStatus.PENDING, null, List.of(), "Ticketing in progress");
        }
        return ticketed(id);
    }

    @Override
    public BookResult bookingDetails(String supplierBookingId) {
        Integer left = pendingLeft.get(supplierBookingId);
        if (left != null && left > 0) {
            pendingLeft.put(supplierBookingId, left - 1);
            return new BookResult(supplierBookingId, SupplierBookingStatus.PENDING, null, List.of(), "Ticketing in progress");
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
        return fares.getOrDefault(id, BigDecimal.ZERO).multiply(REFUND_SHARE).setScale(2, RoundingMode.HALF_UP);
    }

    private BookResult ticketed(String id) {
        String tag = Integer.toString(Math.floorMod(id.hashCode(), 1_000_000));
        String pnr = ("MK" + Integer.toString(Math.floorMod(id.hashCode(), 36 * 36 * 36 * 36), 36)).toUpperCase();
        return new BookResult(id, SupplierBookingStatus.TICKETED, pnr, List.of("MOCK-TKT-" + tag), "Ticketed");
    }
}
