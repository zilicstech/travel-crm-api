package com.voyra.crm.service.booking;

import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.models.booking.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mock suppliers' return-trip hints, repeated bookings and ids that carry no fare. */
class MockBookingProvidersCoverageTest {

    private final FlightBookingProviderConfig flightConfig = new FlightBookingProviderConfig();
    private final MockFlightBookingProvider flights = new MockFlightBookingProvider(flightConfig);
    private final HotelBookingProviderConfig hotelConfig = new HotelBookingProviderConfig();
    private final MockHotelBookingProvider hotels = new MockHotelBookingProvider(hotelConfig);

    private static final List<HotelRoomRequest> ONE_ROOM = List.of(new HotelRoomRequest(2, List.of()));

    @Test
    void aReturnFlightUsesItsOwnHintsWhenGiven() {
        FlightReviewRequest r = new FlightReviewRequest();
        r.setOfferId("ON");
        r.setReturnOfferId("RET");
        r.setAdults(1);
        r.setDepartDate("2026-11-01");
        r.setOrigin("AAA");
        r.setDestination("BBB");
        r.setAirline("Onward Air");
        r.setReturnAirline("Back Air");
        r.setReturnAirlineCode("BA");
        r.setReturnFlightNumber("BA 9");
        r.setReturnDepartDate("2026-11-09");

        var back = flights.review(r).itinerary().path("sI").get(1);

        assertThat(back.path("fD").path("aI").path("name").asText()).isEqualTo("Back Air");
        assertThat(back.path("fD").path("aI").path("code").asText()).isEqualTo("BA");
        assertThat(back.path("fD").path("fN").asText()).isEqualTo("BA 9");
        assertThat(back.path("da").path("code").asText()).isEqualTo("BBB");
        assertThat(back.path("dt").asText()).isEqualTo("2026-11-09T18:00");
    }

    @Test
    void aReturnFlightWithoutHintsFliesTheSameAirlineBackThreeDaysLater() {
        FlightReviewRequest r = new FlightReviewRequest();
        r.setOfferId("ON");
        r.setReturnOfferId("RET");
        r.setAdults(1);
        r.setDepartDate("2026-11-01");
        r.setOrigin(" ");
        r.setAirline("Onward Air");

        var sI = flights.review(r).itinerary().path("sI");

        assertThat(sI.get(0).path("da").path("code").asText()).isEqualTo("DEL");
        assertThat(sI.get(1).path("fD").path("aI").path("name").asText()).isEqualTo("Onward Air");
        assertThat(sI.get(1).path("fD").path("fN").asText()).isEqualTo("102");
        assertThat(sI.get(1).path("dt").asText()).isEqualTo("2026-11-04T18:00");
    }

    @Test
    void bookingTheSameFlightReviewTwiceKeepsItsPendingCount() {
        flightConfig.getMock().setPendingPolls(1);
        BookRequest req = new BookRequest("mock-rv-x", new BigDecimal("100.00"), List.of(), "pax-a@example.test", "0000000000");

        String id = flights.book(req).supplierBookingId();
        flights.book(req);

        assertThat(flights.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(flights.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.TICKETED);
    }

    @Test
    void aFlightIdWithoutAFareRefundsNothing() {
        assertThat(flights.cancelQuote("review-id-after-timeout").refund()).isEqualByComparingTo("0.00");
        assertThat(flights.cancelQuote("mock-bk-abc-notanumber").refund()).isEqualByComparingTo("0.00");
    }

    @Test
    void anUnknownHotelOptionIsPricedFromItsIdPerNightAndRoom() {
        LocalDate in = LocalDate.now().plusDays(30);

        FareReview r = hotels.review("opt-x", "unknown-hotel", in, in.plusDays(2), ONE_ROOM);

        long perNight = 3000 + Math.floorMod("opt-x".hashCode(), 5000);
        assertThat(r.totalFare()).isEqualByComparingTo(BigDecimal.valueOf(perNight * 2));
    }

    @Test
    void bookingTheSameHotelReviewTwiceKeepsItsPendingCount() {
        hotelConfig.getMock().setPendingPolls(1);
        BookRequest req = new BookRequest("not-a-mock-review", new BigDecimal("50.00"), List.of(), "pax-a@example.test", "0000000000");

        String id = hotels.book(req).supplierBookingId();
        hotels.book(req);

        assertThat(id).contains("-none-50.00");
        assertThat(hotels.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(hotels.bookingDetails(id).status()).isEqualTo(SupplierBookingStatus.TICKETED);
        // "none" is no date, so nothing can be worked out to refund.
        assertThat(hotels.cancelQuote(id).refund()).isNull();
        assertThat(hotels.cancelQuote(id).message()).isNull();
    }

    @Test
    void aHotelIdWithoutATotalConfirmsWithoutOne() {
        assertThat(hotels.bookingDetails("mock-hbk-abc").amount()).isNull();
        assertThat(hotels.bookingDetails("mock-hbk-abc-20261201-oops").amount()).isNull();
        assertThat(hotels.bookingDetails("other-id").amount()).isNull();
    }
}
