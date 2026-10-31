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
    void review_isDeterministicWithBaseFareAt85PercentAndAFreshReviewId() {
        FareReview a = provider.review("mock-indigo-0615-PUBLISHED", 2, 1, 0);
        FareReview b = provider.review("mock-indigo-0615-PUBLISHED", 2, 1, 0);
        assertThat(a).usingRecursiveComparison().ignoringFields("expiresAt", "reviewId").isEqualTo(b);
        assertThat(a.baseFare()).isEqualByComparingTo(a.totalFare().multiply(new BigDecimal("0.85")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(a.reviewId()).startsWith("mock-rv-mock-indigo-0615-PUBLISHED-").isNotEqualTo(b.reviewId());
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
        assertThat(provider.bookingDetails(MockFlightBookingProvider.bookingId(review.reviewId(), review.totalFare())).status()).isEqualTo(SupplierBookingStatus.TICKETED);
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

    @Test
    void bookingDetailsByReviewIdReportsNoChargedAmount() {
        FareReview review = provider.review("o", 1, 0, 0);

        assertThat(provider.bookingDetails(review.reviewId()).amount()).isNull();
        assertThat(provider.bookingDetails(MockFlightBookingProvider.bookingId(review.reviewId(), review.totalFare())).amount())
                .isEqualByComparingTo(review.totalFare());
    }

    @Test
    void reviewWithDisplayHintsSynthesisesAnSiItinerary() {
        com.voyra.crm.dto.FlightReviewRequest r = new com.voyra.crm.dto.FlightReviewRequest();
        r.setOfferId("o1");
        r.setAdults(1);
        r.setAirline("IndiGo");
        r.setAirlineCode("6E");
        r.setFlightNumber("201");
        r.setOrigin("DEL");
        r.setDestination("BOM");
        r.setDepartDate("2026-12-01");
        r.setDepartTime("06:00");
        r.setArriveTime("08:10");

        var itinerary = provider.review(r).itinerary();

        var seg = itinerary.findValues("sI").get(0).get(0);
        assertThat(seg.path("da").path("code").asText()).isEqualTo("DEL");
        assertThat(seg.path("aa").path("code").asText()).isEqualTo("BOM");
        assertThat(seg.path("dt").asText()).isEqualTo("2026-12-01T06:00");
        assertThat(seg.path("at").asText()).isEqualTo("2026-12-01T08:10");
        assertThat(seg.path("fD").path("aI").path("code").asText()).isEqualTo("6E");
        assertThat(seg.path("fD").path("aI").path("name").asText()).isEqualTo("IndiGo");
        assertThat(seg.path("fD").path("fN").asText()).isEqualTo("201");
        assertThat(seg.path("duration").asInt()).isEqualTo(130);
        assertThat(seg.has("isRs")).isFalse();
        var fare = itinerary.path("totalPriceList").get(0);
        assertThat(fare.path("fareIdentifier").asText()).isEqualTo("PUBLISHED");
        var adult = fare.path("fd").path("ADULT");
        assertThat(adult.path("cc").asText()).isEqualTo("ECONOMY");
        assertThat(adult.path("rT").asInt()).isEqualTo(1);
        assertThat(adult.path("bI").path("iB").asText()).isEqualTo("15 Kg");
        assertThat(adult.path("bI").path("cB").asText()).isEqualTo("7 Kg");

        r.setReturnOfferId("o2");
        var back = provider.review(r).itinerary().findValues("sI").get(0).get(1);
        assertThat(back.path("isRs").asBoolean()).isTrue();
        assertThat(back.path("da").path("code").asText()).isEqualTo("BOM");
    }

    /** platform_booking.supplier_booking_id held 80 characters; the old id repeated the ~70-character review id. */
    @Test
    void theBookingIdIsShortAndStillCarriesTheFareForTheRefund() {
        FareReview review = provider.review("mock-airindia-09:30-FLEXI_PLUS-PREMIUM_ECONOMY", 2, 1, 1);
        String id = book(review).supplierBookingId();

        assertThat(id).startsWith("mock-bk-").hasSizeLessThanOrEqualTo(40);
        assertThat(book(review).supplierBookingId()).isEqualTo(id); // same review, same id
        assertThat(new MockFlightBookingProvider(new FlightBookingProviderConfig()).cancelQuote(id).refund())
                .isEqualByComparingTo(review.totalFare().multiply(new BigDecimal("0.80")).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** With no reprice the agency sees no "price changed": the review prices an offer exactly as the search showed it. */
    @Test
    void withNoRepriceTheReviewTotalIsTheSearchTotal() {
        for (com.voyra.crm.enums.FlightCabin cabin : com.voyra.crm.enums.FlightCabin.values()) {
            com.voyra.crm.dto.FlightSearchQuery query = new com.voyra.crm.dto.FlightSearchQuery();
            query.setOrigin("DEL");
            query.setDestination("BOM");
            query.setDepartDate(java.time.LocalDate.of(2026, 12, 15));
            query.setCabin(cabin);
            query.setAdults(2);
            query.setChildren(1);
            query.setInfants(1);
            for (com.voyra.crm.dto.FlightOfferResponse offer : new com.voyra.crm.service.MockFlightSearchProvider().search(query)) {
                FareReview review = provider.review(offer.getOfferId(), 2, 1, 1);
                assertThat(review.totalFare()).as(offer.getOfferId()).isEqualByComparingTo(offer.getTotalPrice());
                assertThat(review.baseFare()).as(offer.getOfferId()).isEqualByComparingTo(offer.getBaseFare());
            }
        }
    }

    /** R1: like Tripjack, the mock always returns an sI itinerary, with the departure date whenever the request gives one. */
    @Test
    void reviewAlwaysReturnsAnItineraryCarryingTheDepartureDate() {
        com.voyra.crm.dto.FlightReviewRequest r = new com.voyra.crm.dto.FlightReviewRequest();
        r.setOfferId("mock-indigo-0615-PUBLISHED");
        r.setAdults(1);
        r.setDepartDate("2026-12-01");
        FareReview review = provider.review(r);
        assertThat(review.itinerary()).isNotNull();
        assertThat(review.itinerary().path("sI").get(0).path("dt").asText()).startsWith("2026-12-01");

        r.setDepartDate(null); // no hints at all: still a complete segment the mirror can use, never "nullT..."
        com.fasterxml.jackson.databind.JsonNode seg = provider.review(r).itinerary().path("sI").get(0);
        assertThat(seg.path("dt").asText()).isEqualTo(java.time.LocalDate.now().plusDays(30) + "T06:00");
        assertThat(seg.path("da").path("code").asText()).isEqualTo("DEL");
        assertThat(seg.path("aa").path("code").asText()).isEqualTo("BOM");
    }

    @Test
    void cancelSwitches_timeoutAndRefuse_offIsUnchanged() {
        String id = "mock-bk-abc-1000.00";
        assertThat(provider.cancel(id).accepted()).isTrue();
        config.getMock().setCancelTimeout(true);
        assertThatThrownBy(() -> provider.cancel(id)).isInstanceOf(SupplierTimeoutException.class);
        config.getMock().setCancelTimeout(false);
        config.getMock().setCancelFail(true);
        CancelResult refused = provider.cancel(id);
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.message()).isEqualTo("Mock: cancellation not allowed");
        config.getMock().setCancelFail(false);
        assertThat(provider.cancel(id).accepted()).isTrue();
    }

    @Test
    void aRoundTripReviewSumsBothWaysAndBuildsTwoSegmentsWithAShortId() {
        String onward = "mock-airindia-09:30-FLEXI_PLUS-PREMIUM_ECONOMY";
        String back = onward + "-R";
        com.voyra.crm.dto.FlightReviewRequest r = new com.voyra.crm.dto.FlightReviewRequest();
        r.setOfferId(onward);
        r.setReturnOfferId(back);
        r.setAdults(2);
        r.setOrigin("DEL");
        r.setDestination("BOM");
        r.setDepartDate("2026-12-01");
        r.setReturnFlightNumber("AI-210");
        r.setReturnDepartDate("2026-12-04");
        r.setReturnDepartTime("18:00");
        r.setReturnArriveTime("20:10");

        FareReview review = provider.review(r);

        assertThat(review.totalFare()).isEqualByComparingTo(provider.review(onward, 2, 0, 0).totalFare()
                .add(provider.review(back, 2, 0, 0).totalFare()));
        assertThat(review.reviewId()).startsWith("mock-rv-" + onward + "-").doesNotContain("-R-").hasSizeLessThan(120);
        var sI = review.itinerary().path("sI");
        assertThat(sI).hasSize(2);
        assertThat(sI.get(0).path("da").path("code").asText()).isEqualTo("DEL");
        assertThat(sI.get(1).path("da").path("code").asText()).isEqualTo("BOM");
        assertThat(sI.get(1).path("aa").path("code").asText()).isEqualTo("DEL");
        assertThat(sI.get(1).path("dt").asText()).isEqualTo("2026-12-04T18:00");
        assertThat(sI.get(1).path("fD").path("fN").asText()).isEqualTo("AI-210");
    }
}
