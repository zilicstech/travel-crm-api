package com.voyra.crm.service.booking;

import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockFlightBookingProviderTest {

    private final FlightBookingProviderConfig config = new FlightBookingProviderConfig();
    private final MockFlightBookingProvider provider = new MockFlightBookingProvider(config);

    private BookResult book(FareReview review) {
        return provider.book(new BookRequest(review.reviewId(), review.totalFare(), List.of(), "a@b.com", "9999999999"));
    }

    @Test
    void review_isDeterministicWithBaseFareAt85Percent() {
        FareReview a = provider.review("mock-indigo-0615-PUBLISHED", 2, 1, 0);
        FareReview b = provider.review("mock-indigo-0615-PUBLISHED", 2, 1, 0);
        assertThat(a).usingRecursiveComparison().ignoringFields("expiresAt").isEqualTo(b);
        assertThat(a.baseFare()).isEqualByComparingTo(a.totalFare().multiply(new BigDecimal("0.85")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(a.reviewId()).isEqualTo("mock-rv-mock-indigo-0615-PUBLISHED");
    }

    @Test
    void repricePct_raisesTotalFare() {
        BigDecimal before = provider.review("o", 1, 0, 0).totalFare();
        config.getMock().setRepricePct(new BigDecimal("10"));
        assertThat(provider.review("o", 1, 0, 0).totalFare()).isEqualByComparingTo(before.multiply(new BigDecimal("1.10")));
    }

    @Test
    void fareExpired_throwsOnReview() {
        config.getMock().setFareExpired(true);
        assertThatThrownBy(() -> provider.review("o", 1, 0, 0)).isInstanceOf(FareUnavailableException.class);
    }

    @Test
    void book_ticketsImmediatelyByDefault() {
        BookResult r = book(provider.review("o", 1, 0, 0));
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(r.pnr()).isNotBlank();
        assertThat(r.ticketNumbers()).hasSize(1);
    }

    @Test
    void pendingPolls_returnsPendingNTimesThenTicketed() {
        config.getMock().setPendingPolls(2);
        BookResult booked = book(provider.review("o", 1, 0, 0));
        assertThat(booked.status()).isEqualTo(SupplierBookingStatus.PENDING);
        String id = booked.supplierBookingId();
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.TICKETED);
    }

    @Test
    void bookTimeout_throwsButDetailsSayTicketed() {
        config.getMock().setBookTimeout(true);
        FareReview review = provider.review("o", 1, 0, 0);
        assertThatThrownBy(() -> book(review)).isInstanceOf(SupplierTimeoutException.class);
        assertThat(provider.bookingDetails("mock-bk-" + review.reviewId() + "-" + review.totalFare().toPlainString()).status()).isEqualTo(SupplierBookingStatus.TICKETED);
    }

    @Test
    void bookFail_returnsCleanFailed() {
        config.getMock().setBookFail(true);
        BookResult r = book(provider.review("o", 1, 0, 0));
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(r.message()).isEqualTo("Mock: fare sold out");
    }

    @Test
    void refund_survivesARestart() {
        FareReview review = provider.review("o", 2, 0, 0);
        String id = book(review).supplierBookingId();
        var fresh = new MockFlightBookingProvider(new FlightBookingProviderConfig());
        assertThat(fresh.cancelQuote(id).refund()).isEqualByComparingTo(review.totalFare().multiply(new BigDecimal("0.80")));
    }

    @Test
    void cancel_refundsEightyPercentOfFare() {
        FareReview review = provider.review("o", 2, 0, 0);
        String id = book(review).supplierBookingId();
        BigDecimal expected = review.totalFare().multiply(new BigDecimal("0.80"));
        assertThat(provider.cancelQuote(id).refund()).isEqualByComparingTo(expected);
        CancelResult c = provider.cancel(id);
        assertThat(c.accepted()).isTrue();
        assertThat(c.refund()).isEqualByComparingTo(expected);
    }
}
