package com.voyra.crm.service.booking;

import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.MockHotelSearchProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockHotelBookingProviderTest {

    private static final LocalDate IN = LocalDate.now().plusDays(30);
    private static final LocalDate OUT = IN.plusDays(3);

    private static final List<HotelRoomRequest> ONE_ROOM = List.of(new HotelRoomRequest(2, List.of()));
    /** Two rooms: 2 adults + a child of 6, and 1 adult. */
    private static final List<HotelRoomRequest> TWO_ROOMS = List.of(new HotelRoomRequest(2, List.of(6)), new HotelRoomRequest(1, List.of()));

    private final HotelBookingProviderConfig config = new HotelBookingProviderConfig();
    private final MockHotelBookingProvider provider = new MockHotelBookingProvider(config);

    private FareReview review() {
        return provider.review("mock-mock-ibis-budget-std", "mock-ibis-budget", IN, OUT, ONE_ROOM);
    }

    private BookResult book(FareReview r) {
        return provider.book(new BookRequest(r.reviewId(), r.totalFare(), List.of(), "a@b.com", "9999999999"));
    }

    @Test
    void review_baseIs82PercentFreshIdAndFreeCancellationDeadlineIsCheckInMinusTwoDays() {
        FareReview a = review();
        FareReview b = review();
        assertThat(a.baseFare()).isEqualByComparingTo(a.totalFare().multiply(new BigDecimal("0.82")).setScale(2, RoundingMode.HALF_UP));
        assertThat(a.reviewId()).isNotEqualTo(b.reviewId());
        assertThat(a.freeCancellationUntil()).isEqualTo(IN.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(a.cancellationPolicy()).contains(IN.minusDays(2).toString());
    }

    /** With no reprice the agency sees no "price changed": the review prices every option exactly as the detail page showed it. */
    @Test
    void withNoRepriceTheReviewTotalIsTheSearchTotal() {
        MockHotelSearchProvider search = new MockHotelSearchProvider();
        HotelSearchQuery q = new HotelSearchQuery();
        q.setCity("Goa");
        q.setCheckIn(IN);
        q.setCheckOut(OUT);
        q.setRooms(2);
        q.setAdults(3);
        q.setChildren(1);
        q.setChildAges(List.of(6));
        for (HotelOfferResponse offer : search.search(q)) {
            assertThat(offer.getBaseFare()).isEqualByComparingTo(offer.getPrice().multiply(new BigDecimal("0.82")).setScale(2, RoundingMode.HALF_UP));
            for (HotelDetailResponse.RoomOption o : search.detail(offer.getHotelId(), q).getOptions()) {
                FareReview r = provider.review(o.getOptionId(), offer.getHotelId(), IN, OUT, TWO_ROOMS);
                assertThat(r.totalFare()).as(o.getOptionId()).isEqualByComparingTo(o.getPrice());
                assertThat(r.baseFare()).as(o.getOptionId()).isEqualByComparingTo(o.getBaseFare());
            }
        }
    }

    @Test
    void repricePct_raisesTotalAndFareExpiredThrows() {
        BigDecimal before = review().totalFare();
        config.getMock().setRepricePct(new BigDecimal("10"));
        assertThat(review().totalFare()).isEqualByComparingTo(before.multiply(new BigDecimal("1.10")));
        config.getMock().setFareExpired(true);
        assertThatThrownBy(this::review).isInstanceOf(FareUnavailableException.class);
    }

    @Test
    void book_confirmsImmediatelyByDefault() {
        BookResult r = book(review());
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(r.pnr()).isNotBlank();
    }

    @Test
    void pendingPolls_returnsPendingNTimesThenConfirmed() {
        config.getMock().setPendingPolls(2);
        String id = book(review()).supplierBookingId();
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.TICKETED);
    }

    @Test
    void bookTimeout_throwsAndBookFailIsCleanFailed() {
        FareReview r = review();
        config.getMock().setBookTimeout(true);
        assertThatThrownBy(() -> book(r)).isInstanceOf(SupplierTimeoutException.class);
        assertThat(provider.bookingDetails(r.reviewId()).status()).isEqualTo(SupplierBookingStatus.TICKETED);
        config.getMock().setBookTimeout(false);
        config.getMock().setBookFail(true);
        BookResult failed = book(r);
        assertThat(failed.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(failed.message()).isNotBlank();
    }

    @Test
    void theBookingIdIsShortAndCarriesTheTotalForTheRefund() {
        FareReview r = review();
        String id = book(r).supplierBookingId();
        assertThat(id).startsWith("mock-hbk-").hasSizeLessThanOrEqualTo(60);
        assertThat(id).doesNotContain(r.reviewId());
        // a fresh provider (restart) still quotes and refunds the full total before the deadline
        MockHotelBookingProvider restarted = new MockHotelBookingProvider(new HotelBookingProviderConfig());
        assertThat(restarted.cancelQuote(id).refund()).isEqualByComparingTo(r.totalFare());
        assertThat(restarted.cancel(id).refund()).isEqualByComparingTo(r.totalFare());
        assertThat(restarted.cancel(id).accepted()).isTrue();
    }

    @Test
    void afterTheFreeCancellationDeadlineTheRefundIsZero() {
        FareReview late = provider.review("mock-mock-ibis-budget-std", "mock-ibis-budget", LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), ONE_ROOM);
        assertThat(late.freeCancellationUntil()).isBefore(Instant.now());
        assertThat(provider.cancelQuote(book(late).supplierBookingId()).refund()).isEqualByComparingTo("0");
    }

    @Test
    void anIdThatCarriesNoFareHasAnUnknownRefund() {
        assertThat(provider.cancelQuote("some-review-id").refund()).isNull();
    }
}
