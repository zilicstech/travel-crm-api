package com.voyra.crm.service.booking;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.VoyraLegal;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Bodies are built from the spec section 7.3 table, not real fixtures - unverified until the slice 0 spike. */
class TripjackFlightBookingProviderTest {

    private static final String BASE = "https://apitest.tripjack.com";

    /** The supplier row every call is given. */
    private static final ResolvedCredential ROW =
            new ResolvedCredential("s1", "Tripjack", SupplierProvider.TRIPJACK, SupplierEnvironment.UAT, BASE, "test-key", null, null);

    private MockRestServiceServer server;
    private PlatformSettingService settings;
    private TripjackFlightBookingProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        settings = mock(PlatformSettingService.class);
        provider = new TripjackFlightBookingProvider(builder, builder, builder, settings);
    }

    private static final String OK = "{\"status\":{\"success\":true,\"httpStatus\":200}";

    /** Two rows of one integration: each call goes to the base URL and key of the row it is given, never a stored one. */
    @Test
    void callsWithTheGivenRowsKeyAndHost() {
        ResolvedCredential live = new ResolvedCredential("s2", "Tripjack live", SupplierProvider.TRIPJACK, SupplierEnvironment.PROD,
                "https://api.tripjack.com", "live-key", null, null);
        server.expect(requestTo("https://api.tripjack.com/oms/v1/booking-details")).andExpect(header("apikey", "live-key"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andExpect(header("apikey", "test-key"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"}}", MediaType.APPLICATION_JSON));

        provider.bookingDetails("TJB1", live);
        provider.bookingDetails("TJB1", ROW);

        server.verify();
        assertThat(provider.provider()).isEqualTo(SupplierProvider.TRIPJACK);
        assertThat(provider.cancelIsAsync()).isTrue();
    }

    @Test
    void review_sendsPriceIdAndMapsFareAndRules() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.priceIds[0]").value("off-1"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJS1\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":9000.5,\"BF\":7000}}},\"tripInfos\":[{\"x\":1}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andExpect(jsonPath("$.id").value("TJS1"))
                .andExpect(jsonPath("$.flowType").value("REVIEW"))
                .andRespond(withSuccess(OK + ",\"fareRule\":{\"cancel\":\"Rs 3000\"}}", MediaType.APPLICATION_JSON));

        FareReview r = provider.review(List.of("off-1"), 1, 0, 0, ROW);

        server.verify();
        assertThat(r.reviewId()).isEqualTo("TJS1");
        assertThat(r.totalFare()).isEqualByComparingTo("9000.50");
        assertThat(r.baseFare()).isEqualByComparingTo("7000");
        assertThat(r.fareRules()).contains("Rs 3000");
        assertThat(r.itinerary().isArray()).isTrue();
        assertThat(r.documentIdRequired()).isFalse(); // no conditions.dc in the answer
    }

    @Test
    void review_readsTheMandatoryDocumentIdFlag() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(OK + ",\"bookingId\":\"TJS1\","
                + "\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":9000.5,\"BF\":7000}}},"
                + "\"conditions\":{\"dc\":{\"ida\":true,\"idm\":true}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.review(List.of("off-1"), 1, 0, 0, ROW).documentIdRequired()).isTrue();
    }

    @Test
    void review_failureIsFareUnavailable() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Fare expired\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW))
                .isInstanceOf(FareUnavailableException.class).hasMessage("The airline could not confirm this fare"); // never the supplier's text
    }

    @Test
    void review_missingFieldsDoNotBlowUp() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW)).isInstanceOf(SupplierException.class);
    }

    private BookRequest request() {
        PaxDetail pax = new PaxDetail("ADULT", "Mr", "Asha", "Rao", LocalDate.of(1990, 1, 2), "P1234567", LocalDate.of(2030, 1, 1), "IN", "STU-118");
        return new BookRequest("TJS1", new BigDecimal("9000.50"), List.of(pax), "a@b.com", "9999999999");
    }

    @Test
    void book_sendsSpecBodyAndReturnsPending() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookingId").value("TJS1"))
                .andExpect(jsonPath("$.paymentInfos[0].amount").value(9000.5))
                .andExpect(jsonPath("$.travellerInfo[0].fN").value("Asha"))
                .andExpect(jsonPath("$.travellerInfo[0].pt").value("ADULT"))
                .andExpect(jsonPath("$.travellerInfo[0].di").value("STU-118"))
                .andExpect(jsonPath("$.deliveryInfo.emails[0]").value("a@b.com"))
                .andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("9999999999"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJS1\"}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request(), ROW);
        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(r.supplierBookingId()).isEqualTo("TJS1");
    }

    @Test
    void book_successFalseIsFailedWithMessage() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Insufficient supplier deposit\"}]}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request(), ROW);
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(r.message()).isEqualTo("Insufficient supplier deposit");
    }

    @Test
    void emptyOkBodyOnBookIsUnknownNotFailed() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess("{\"status\":{\"success\":\"no\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.book(request(), ROW)).isInstanceOf(SupplierException.class);
        assertThatThrownBy(() -> provider.book(request(), ROW)).isInstanceOf(SupplierException.class);
    }

    /** Live 2026-10-30: Tripjack answers a sold-out fare's review with HTTP 400 errCode 1000; the agent gets "pick another fare", not a 502. */
    @Test
    void a4xxOnReviewIsAnUnavailableFareNotAnUnreachableSupplier() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":{\"success\":false,\"httpStatus\":400},\"errors\":[{\"errCode\":\"1000\","
                        + "\"message\":\"Requested flight is no longer available. Please try different flight\"}]}"));
        assertThatThrownBy(() -> provider.review(List.of("P1"), 1, 0, 0, ROW)).isInstanceOf(FareUnavailableException.class)
                .hasMessageContaining("pick another fare");
    }

    /** Live 2026-10-30: a 400 on book left no order at Tripjack, so it is a clean failure that releases the hold at once. */
    @Test
    void a4xxOnBookIsAFailureWithTheSuppliersCodeAndA5xxStaysUnknown() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":{\"success\":false},\"errors\":[{\"errCode\":\"1024\",\"message\":\"Invalid title\"}]}"));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        BookResult refused = provider.book(request(), ROW);
        assertThat(refused.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(refused.message()).contains("1024").contains("Invalid title");
        assertThat(provider.book(request(), ROW).status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThatThrownBy(() -> provider.book(request(), ROW)).isInstanceOf(SupplierException.class);
    }

    @Test
    void unknownOrderStatusIsPending() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"ABORTED\"}}", MediaType.APPLICATION_JSON));
        assertThat(provider.bookingDetails("TJS1", ROW).status()).isEqualTo(SupplierBookingStatus.PENDING);
    }

    @Test
    void restClientBuilderQualifierReachesTheConstructors() {
        for (Class<?> c : List.of(TripjackFlightBookingProvider.class, com.voyra.crm.service.tripjack.TripjackFlightSearchProvider.class)) {
            var ctor = c.getDeclaredConstructors()[0];
            var q = java.util.Arrays.stream(ctor.getParameters()).filter(p -> p.getType() == RestClient.Builder.class)
                    .findFirst().orElseThrow().getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class);
            assertThat(q).as(c.getSimpleName()).isNotNull();
            assertThat(q.value()).isEqualTo("tripjackRestClientBuilder");
        }
    }

    @Test
    void book_transportTimeoutIsSupplierTimeout() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(req -> { throw new SocketTimeoutException("Read timed out"); });
        assertThatThrownBy(() -> provider.book(request(), ROW)).isInstanceOf(SupplierTimeoutException.class);
    }

    @Test
    void book_httpErrorIsPlainSupplierException() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> provider.book(request(), ROW))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class);
    }

    @Test
    void bookingDetails_mapsTicketedPnrAndTickets() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andExpect(jsonPath("$.bookingId").value("TJS1"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"},\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                        + "{\"pnrDetails\":{\"BLR-HKT\":\"ABC123\"},\"ticketNumberDetails\":{\"BLR-HKT\":\"176-111\"}},"
                        + "{\"pnrDetails\":{\"BLR-HKT\":\"ABC123\"},\"ticketNumberDetails\":{\"BLR-HKT\":\"176-222\"}}]}}}",
                        MediaType.APPLICATION_JSON));
        BookResult r = provider.bookingDetails("TJS1", ROW);
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(r.pnr()).isEqualTo("ABC123");
        assertThat(r.ticketNumbers()).containsExactly("176-111", "176-222");
    }

    @Test
    void bookingDetails_statusMappingAndMissingFields() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"PENDING\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"FAILED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"CANCELLED\"}}", MediaType.APPLICATION_JSON));
        BookResult pending = provider.bookingDetails("TJS1", ROW);
        assertThat(pending.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(pending.pnr()).isNull();
        assertThat(pending.ticketNumbers()).isEmpty();
        assertThat(provider.bookingDetails("TJS1", ROW).status()).isEqualTo(SupplierBookingStatus.FAILED);
        // a cancelled booking is how an accepted amendment is confirmed - not a failed booking
        assertThat(provider.bookingDetails("TJS1", ROW).status()).isEqualTo(SupplierBookingStatus.CANCELLED);
    }

    private static String fixture(String path) throws java.io.IOException {
        try (var in = TripjackFlightBookingProviderTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Fixture built from Tripjack's documented shape (the probe got 403), to be corrected by slice 0. */
    @Test
    void roundTripReviewSendsBothPriceIdsAsOneReview() throws Exception {
        server.expect(requestTo(BASE + "/fms/v1/review"))
                .andExpect(jsonPath("$.priceIds.length()").value(2))
                .andExpect(jsonPath("$.priceIds[0]").value("ON-6E2031-PUB"))
                .andExpect(jsonPath("$.priceIds[1]").value("RT-6E2032-PUB"))
                .andRespond(withSuccess(fixture("tripjack/flight-review-roundtrip.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        FareReview r = provider.review(List.of("ON-6E2031-PUB", "RT-6E2032-PUB"), 1, 0, 0, ROW);

        server.verify();
        assertThat(r.reviewId()).isEqualTo("TJS101900012345");
        assertThat(r.totalFare()).isEqualByComparingTo("10700");
        assertThat(r.itinerary().findValues("sI")).hasSize(2);
    }

    @Test
    void aRefusedPairGetsItsOwnMessage() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Invalid combination\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review(List.of("a", "b"), 1, 0, 0, ROW))
                .isInstanceOf(FareUnavailableException.class).hasMessage(AgencySupplierMessage.NOT_CONFIRMED_PAIR);
    }

    @Test
    void twoPaxRoundTripMapsTicketPerPaxAndSector() throws Exception {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(
                fixture("tripjack/booking-details-roundtrip-two-pax.json"), MediaType.APPLICATION_JSON));
        BookResult r = provider.bookingDetails("TJS1", ROW);
        assertThat(r.lines()).containsExactly(
                new com.voyra.crm.models.booking.TicketLine(0, "DEL-BOM", "ABC123", "176-111"),
                new com.voyra.crm.models.booking.TicketLine(0, "BOM-DEL", "XYZ789", "176-112"),
                new com.voyra.crm.models.booking.TicketLine(1, "DEL-BOM", "ABC123", "176-221"),
                new com.voyra.crm.models.booking.TicketLine(1, "BOM-DEL", "XYZ789", null));
        assertThat(r.pnr()).isEqualTo("ABC123 / XYZ789");
        assertThat(r.ticketNumbers()).containsExactly("176-111", "176-112", "176-221");
    }

    @Test
    void bookingDetails_keepsEveryDistinctSectorPnr() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(
                OK + ",\"order\":{\"status\":\"SUCCESS\"},\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                        + "{\"pnrDetails\":{\"DEL-BOM\":\"ABC123\",\"BOM-DEL\":\"XYZ789\"}},"
                        + "{\"pnrDetails\":{\"DEL-BOM\":\"ABC123\",\"BOM-DEL\":\"XYZ789\"}}]}}}",
                MediaType.APPLICATION_JSON));
        assertThat(provider.bookingDetails("TJS1", ROW).pnr()).isEqualTo("ABC123 / XYZ789");
    }

    // ---------------------------------------------------------------- coverage: review edges, sparse bookings, odd answers

    @Test
    void aReviewThatCannotReachTheSupplierIsNotAnUnavailableFare() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(FareUnavailableException.class)
                .hasMessage("Tripjack review call failed (HTTP 503)");
    }

    @Test
    void aRefused4xxPairReviewGetsThePairMessage() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> provider.review(List.of("ON", "RET"), 1, 0, 0, ROW))
                .isInstanceOf(FareUnavailableException.class).hasMessage(AgencySupplierMessage.NOT_CONFIRMED_PAIR);
    }

    @Test
    void aReviewWithABlankIdOrUnreadableTotalIsIncomplete() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\" \",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS1\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":\"abc\"}}}}", MediaType.APPLICATION_JSON));

        String message = "Tripjack review response is missing the review id or total fare";
        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW)).isInstanceOf(SupplierException.class).hasMessage(message);
        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW)).isInstanceOf(SupplierException.class).hasMessage(message);
    }

    @Test
    void aReviewWithoutTripInfosBaseOrRulesStillReviews() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS2\",\"conditions\":{\"x\":1},\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100,\"BF\":null}}}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + ",\"fareRule\":null}", MediaType.APPLICATION_JSON));

        FareReview r = provider.review(List.of("off-1"), 1, 0, 0, ROW);

        assertThat(r.itinerary()).isNull();
        assertThat(r.baseFare()).isNull();
        assertThat(r.fareRules()).isNull();
    }

    @Test
    void unavailableFareRulesDoNotStopTheReview() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS3\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body("x".repeat(300)));

        assertThat(provider.review(List.of("off-1"), 1, 0, 0, ROW).fareRules()).isNull();
    }

    @Test
    void anEmptyFareRuleAnswerLeavesTheReviewWithoutRules() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS4\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess());

        FareReview r = provider.review(List.of("off-1"), 1, 0, 0, ROW);

        assertThat(r.reviewId()).isEqualTo("TJS4");
        assertThat(r.fareRules()).isNull();
        server.verify();
    }

    @Test
    void aReviewWithANullBookingIdIsIncomplete() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":null,\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.review(List.of("off-1"), 1, 0, 0, ROW))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack review response is missing the review id or total fare");
    }

    @Test
    void aTimeoutWithItsOwnCauseIsStillATimeout() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(req -> {
            SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
            timeout.initCause(new java.io.IOException("socket"));
            throw timeout;
        });

        assertThatThrownBy(() -> provider.bookingDetails("TJS1", ROW))
                .isInstanceOf(SupplierTimeoutException.class).hasMessage("Tripjack booking-details timed out");
    }

    @Test
    void aBookingWithoutContactsOrOptionalPassengerFieldsSendsEmptyLists() {
        PaxDetail bare = new PaxDetail("CHILD", "Ms", "Pax", "Child", null, null, null, null, null);
        server.expect(requestTo(BASE + "/oms/v1/air/book"))
                .andExpect(jsonPath("$.deliveryInfo.emails").isEmpty())
                .andExpect(jsonPath("$.deliveryInfo.contacts").isEmpty())
                .andExpect(jsonPath("$.travellerInfo[0].dob").doesNotExist())
                .andExpect(jsonPath("$.travellerInfo[0].pNum").doesNotExist())
                .andExpect(jsonPath("$.travellerInfo[0].di").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        BookResult r = provider.book(new BookRequest("TJS1", new BigDecimal("100"), List.of(bare), null, null), ROW);

        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
    }

    @Test
    void aBlankEmailIsSentAsGiven() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andExpect(jsonPath("$.deliveryInfo.emails[0]").value(" "))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.book(new BookRequest("TJS1", BigDecimal.ONE, List.of(), " ", "+91 00"), ROW).status())
                .isEqualTo(SupplierBookingStatus.PENDING);
    }

    @Test
    void anUnsuccessfulBookingDetailsAnswerIsStillPending() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Try later\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        BookResult first = provider.bookingDetails("TJS1", ROW);
        BookResult second = provider.bookingDetails("TJS1", ROW);

        assertThat(first.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(first.message()).isEqualTo("Try later");
        assertThat(second.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(second.message()).isNull();
    }

    @Test
    void blankPnrsAndTicketsAreSkippedAndAMissingStatusHasNoMessage() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                + "{\"pnrDetails\":{\"DEL-BOM\":\" \"},\"ticketNumberDetails\":{\"DEL-BOM\":\"\"}}]}}}", MediaType.APPLICATION_JSON));

        BookResult r = provider.bookingDetails("TJS1", ROW);

        assertThat(r.pnr()).isNull();
        assertThat(r.ticketNumbers()).isEmpty();
        assertThat(r.message()).isNull();
    }

    @Test
    void aTransportFailureThatIsNotATimeoutIsAPlainFailureAndJunkIsUnparseable() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(req -> { throw new java.io.IOException("reset"); });
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.bookingDetails("TJS1", ROW))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class)
                .hasMessage("Tripjack booking-details call failed");
        assertThatThrownBy(() -> provider.bookingDetails("TJS1", ROW))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack booking-details returned an unparseable response");
    }

    @Test
    void searchFareRulesAskTheSearchFlowWithThePriceId() {
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andExpect(jsonPath("$.id").value("price-1"))
                .andExpect(jsonPath("$.flowType").value("SEARCH"))
                .andRespond(withSuccess(OK + ",\"fareRule\":{\"cancel\":\"Rs 3000\"}}", MediaType.APPLICATION_JSON));

        assertThat(provider.searchFareRules("price-1", ROW)).contains("Rs 3000");
        server.verify();
    }

    @Test
    void searchFareRulesAreNullWhenTheSupplierRefusesOrSendsNone() {
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.searchFareRules("price-1", ROW)).isNull();
        assertThat(provider.searchFareRules("price-1", ROW)).isNull();
    }

    @Test
    void fastAndSeatBuildersAreQualifiedAndIsolatedFromTheMainClient() {
        var ctor = TripjackFlightBookingProvider.class.getDeclaredConstructors()[0];
        List<String> qualifiers = java.util.Arrays.stream(ctor.getParameters())
                .filter(p -> p.getType() == RestClient.Builder.class)
                .map(p -> p.getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value())
                .toList();
        assertThat(qualifiers).containsExactly("tripjackRestClientBuilder", "tripjackFastRestClientBuilder", "tripjackSeatRestClientBuilder");

        RestClient.Builder main = RestClient.builder();
        RestClient.Builder fast = RestClient.builder();
        RestClient.Builder seat = RestClient.builder();
        MockRestServiceServer mainSrv = MockRestServiceServer.bindTo(main).build();
        MockRestServiceServer fastSrv = MockRestServiceServer.bindTo(fast).build();
        MockRestServiceServer seatSrv = MockRestServiceServer.bindTo(seat).build();
        TripjackFlightBookingProvider isolated = new TripjackFlightBookingProvider(main, fast, seat, settings);

        mainSrv.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJ-ISO\",\"conditions\":{\"st\":1800,\"isa\":true,\"fsc\":{\"issi\":true}},"
                        + "\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":5000,\"BF\":4000}}}}", MediaType.APPLICATION_JSON));
        fastSrv.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(req -> { throw new SocketTimeoutException("farerule stalled"); });
        seatSrv.expect(requestTo(BASE + "/fms/v1/seat")).andExpect(jsonPath("$.bookingId").value("TJ-ISO"))
                .andRespond(withSuccess(OK + ",\"tripSeatMap\":{\"tripSeat\":{\"1490\":{\"sData\":{\"row\":5,\"column\":7},\"nt\":\"Front row\","
                        + "\"sInfo\":[{\"seatNo\":\"4A\",\"code\":\"4A\",\"isBooked\":false,\"amount\":0,\"isAisle\":false,\"isLegRoom\":false,\"isExitRow\":false,\"seatPosition\":{\"row\":4,\"column\":1}}]}}}}",
                        MediaType.APPLICATION_JSON));

        FareReview r = isolated.review(List.of("P1"), 1, 0, 0, ROW);
        assertThat(r.fareRules()).isNull();
        assertThat(r.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(1700));
        assertThat(r.flightCompliance().seatApplicable()).isTrue();
        assertThat(r.flightCompliance().seatMandatory()).isTrue();

        var sm = isolated.seatMap("B1", "TJ-ISO", Map.of("tripInfos", List.of(Map.of("sI", List.of(
                Map.of("id", "1490", "fD", Map.of("aI", Map.of("code", "6E"), "fN", "6259"), "da", Map.of("code", "BHO"), "aa", Map.of("code", "DEL")))))),
                true, ROW);
        assertThat(sm.isSeatMandatory()).isTrue();
        seatSrv.reset();
        seatSrv.expect(requestTo(BASE + "/fms/v1/seat")).andRespond(withSuccess(
                OK + ",\"tripSeatMap\":{\"tripSeat\":{\"1490\":{\"sInfo\":[{\"code\":\"4B\",\"isBooked\":false,\"amount\":null}]}}}}", MediaType.APPLICATION_JSON));
        assertThat(isolated.seatMap("B1", "TJ-ISO", null, false, ROW).isSeatMandatory()).isFalse();
        assertThat(sm.getSegments()).singleElement().satisfies(seg -> {
            assertThat(seg.getSegmentKey()).isEqualTo("1490");
            assertThat(seg.getAirlineCode()).isEqualTo("6E");
            assertThat(seg.getFlightNumber()).isEqualTo("6259");
            assertThat(seg.getOrigin()).isEqualTo("BHO");
            assertThat(seg.getDestination()).isEqualTo("DEL");
            assertThat(seg.getNote()).isEqualTo("Front row");
            assertThat(seg.getSeats()).singleElement().satisfies(o -> assertThat(o.getSeatNo()).isEqualTo("4A"));
        });
        mainSrv.verify();
        fastSrv.verify();
        seatSrv.verify();
    }

    @Test
    void review_parsesAllComplianceFlagsAndEnforcesConfiguredGst() {
        String body = OK + ",\"bookingId\":\"TJ-COMP\",\"conditions\":{\"isa\":true,\"ifsm\":true,\"ipa\":true,"
                + "\"pcs\":{\"pm\":true,\"pped\":true},\"dob\":{\"adobr\":true},\"iecr\":true,\"gst\":{\"igm\":true}},"
                + "\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":8000,\"BF\":6500}}}}";
        for (VoyraLegal bad : java.util.Arrays.asList(null, new VoyraLegal("Voyra", null, null, null), new VoyraLegal(null, "29AAAAA0000A1Z5", null, null))) {
            server.reset();
            when(settings.voyraLegal()).thenReturn(bad);
            server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> provider.review(List.of("P1"), 1, 0, 0, ROW))
                    .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("GST_NOT_CONFIGURED"));
        }
        server.reset();
        when(settings.voyraLegal()).thenReturn(new VoyraLegal("Voyra Travel Tech Pvt Ltd", "29AAAAA0000A1Z5", "12 MG Road", "29"));
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        FareReview ok = provider.review(List.of("P1"), 1, 0, 0, ROW);
        assertThat(ok.flightCompliance()).isEqualTo(new FlightCompliance(true, true, false, true, true, true, true, true, true));
    }

    @Test
    void seatMap_handlesRefusalNotApplicableSoldOutAndSparseMetadata() {
        // 4xx refusal (e.g. 1056) -> empty, non-mandatory
        server.expect(requestTo(BASE + "/fms/v1/seat")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errors\":[{\"errCode\":\"1056\",\"message\":\"Seat selection not applicable\"}]}"));
        assertThat(provider.seatMap("B1", "TJ1", null, true, ROW).isSeatMandatory()).isFalse();

        // 200 with status.success = false -> empty, non-mandatory
        server.reset();
        server.expect(requestTo(BASE + "/fms/v1/seat")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Seat map unavailable\"}]}", MediaType.APPLICATION_JSON));
        assertThat(provider.seatMap("B1", "TJ1", null, true, ROW).getSegments()).isEmpty();

        // 5xx -> rethrows so caller can retry
        server.reset();
        server.expect(requestTo(BASE + "/fms/v1/seat")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> provider.seatMap("B1", "TJ1", null, true, ROW)).isInstanceOf(SupplierException.class);

        // All seats booked + missing code/seatNo/negative amount + unmatched itinerary segment -> downgrades seatMandatory to false
        server.reset();
        Map<String, Object> itinerary = Map.of("sI", List.of(
                Map.of("da", Map.of(), "aa", Map.of()),
                Map.of("id", "SEG-NULL")));
        server.expect(requestTo(BASE + "/fms/v1/seat")).andRespond(withSuccess(OK + ",\"tripSeatMap\":{\"tripSeat\":{"
                + "\"SEG-NULL\":null,"
                + "\"EXTRA\":{\"sInfo\":["
                + "{\"code\":\" \",\"seatNo\":\"1A\"},"
                + "{\"code\":\"2B\",\"isBooked\":true,\"amount\":-5,\"seatPosition\":{\"row\":2,\"column\":2}}"
                + "]}}}}", MediaType.APPLICATION_JSON));
        var res = provider.seatMap("B1", "TJ1", itinerary, true, ROW);
        assertThat(res.isSeatMandatory()).isFalse();
        assertThat(res.getSegments()).singleElement().satisfies(seg -> {
            assertThat(seg.getSegmentKey()).isEqualTo("EXTRA");
            assertThat(seg.getRows()).isEqualTo(2);
            assertThat(seg.getColumns()).isEqualTo(2);
            assertThat(seg.getSeats()).singleElement().satisfies(s -> {
                assertThat(s.getSeatNo()).isEqualTo("2B");
                assertThat(s.getAmount()).isEqualByComparingTo("0.00");
                assertThat(s.isBooked()).isTrue();
            });
        });
    }

    @Test
    void book_sendsNormalizedTitlesPanSeatsEmergencyContactAndGst() {
        when(settings.voyraLegal()).thenReturn(new VoyraLegal(
                "Voyra Global Corporate Travel Solutions Private Limited", "29AAAAA0000A1Z5", "A".repeat(80), "29"));
        PaxDetail adult1 = new PaxDetail("ADULT", "Mrs.", "Priya", "Sharma", LocalDate.of(1992, 3, 10),
                null, null, null, null, "ABCDE1234F", List.of(new SeatSelection("1490", "1A", "1A")));
        PaxDetail adult2 = new PaxDetail("ADULT", "Miss", "Neha", "Sharma", null, null, null, null, null, null, List.of());
        PaxDetail child1 = new PaxDetail("CHILD", "Mstr", "Aarav", "Sharma", LocalDate.of(2018, 5, 1),
                null, null, null, null, null, List.of(new SeatSelection("1490", "1B", "1B")));
        PaxDetail infant = new PaxDetail("INFANT", "Miss", "Diya", "Sharma", LocalDate.of(2025, 6, 1),
                null, null, null, null, null, List.of(new SeatSelection("1490", "1C", "1C")));
        FlightCompliance fc = new FlightCompliance(true, true, false, true, false, false, true, true, true);
        BookRequest req = new BookRequest("TJ1", new BigDecimal("12500.00"), List.of(adult1, adult2, child1, infant),
                "desk@agency.test", "+91 9876543210", List.of(), null, HotelCompliance.NONE, "91", fc);

        server.expect(requestTo(BASE + "/oms/v1/air/book"))
                .andExpect(jsonPath("$.travellerInfo[0].ti").value("Mrs"))
                .andExpect(jsonPath("$.travellerInfo[0].pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.travellerInfo[0].ssrSeatInfos[0].key").value("1490"))
                .andExpect(jsonPath("$.travellerInfo[0].ssrSeatInfos[0].code").value("1A"))
                .andExpect(jsonPath("$.travellerInfo[1].ti").value("Ms"))
                .andExpect(jsonPath("$.travellerInfo[1].ssrSeatInfos").doesNotExist())
                .andExpect(jsonPath("$.travellerInfo[2].ti").value("Master"))
                .andExpect(jsonPath("$.travellerInfo[2].ssrSeatInfos[0].code").value("1B"))
                .andExpect(jsonPath("$.travellerInfo[3].ti").value("Ms"))
                .andExpect(jsonPath("$.travellerInfo[3].ssrSeatInfos").doesNotExist())
                .andExpect(jsonPath("$.contactInfo.ecn").value("Priya Sharma"))
                .andExpect(jsonPath("$.contactInfo.emails[0]").value("desk@agency.test"))
                .andExpect(jsonPath("$.gstInfo.gstNumber").value("29AAAAA0000A1Z5"))
                .andExpect(jsonPath("$.gstInfo.registeredName").value("Voyra Global Corporate Travel Solut"))
                .andExpect(jsonPath("$.gstInfo.mobile").value("919876543210"))
                .andExpect(jsonPath("$.gstInfo.address").value("A".repeat(70)))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJ1\"}", MediaType.APPLICATION_JSON));

        assertThat(provider.book(req, ROW).status()).isEqualTo(SupplierBookingStatus.PENDING);

        // Edge cases: empty passengers with iecr, short legal name/short address/null phone, null address/non-digit phone, and missing legal on book
        server.reset();
        when(settings.voyraLegal()).thenReturn(new VoyraLegal("Voyra", "29AAAAA0000A1Z5", "12 MG Road", "29"));
        server.expect(requestTo(BASE + "/oms/v1/air/book"))
                .andExpect(jsonPath("$.contactInfo.ecn").value("Emergency Contact"))
                .andExpect(jsonPath("$.gstInfo.registeredName").value("Voyra"))
                .andExpect(jsonPath("$.gstInfo.mobile").doesNotExist())
                .andExpect(jsonPath("$.gstInfo.address").value("12 MG Road"))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        provider.book(new BookRequest("TJ2", BigDecimal.ONE, List.of(), null, null, List.of(), null, HotelCompliance.NONE, null, fc), ROW);

        server.reset();
        when(settings.voyraLegal()).thenReturn(new VoyraLegal("Voyra", "29AAAAA0000A1Z5", null, "29"));
        server.expect(requestTo(BASE + "/oms/v1/air/book"))
                .andExpect(jsonPath("$.gstInfo.address").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        provider.book(new BookRequest("TJ2B", BigDecimal.ONE, List.of(), null, "+", List.of(), null, HotelCompliance.NONE, null, fc), ROW);

        for (VoyraLegal missing : java.util.Arrays.asList(null, new VoyraLegal("Voyra", null, null, null), new VoyraLegal(null, "GST", "Addr", null))) {
            server.reset();
            when(settings.voyraLegal()).thenReturn(missing);
            server.expect(requestTo(BASE + "/oms/v1/air/book"))
                    .andExpect(jsonPath("$.gstInfo").doesNotExist())
                    .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
            PaxDetail nullSeatsPax = new PaxDetail("ADULT", null, "A", "B", null, null, null, null, null, null, null);
            provider.book(new BookRequest("TJ3", BigDecimal.ONE, List.of(nullSeatsPax), null, null, List.of(), null, HotelCompliance.NONE, null, fc), ROW);
        }
        assertThat(TripjackFlightBookingProvider.normalizeTitle("CHILD", "Mrs")).isEqualTo("Ms");
        assertThat(TripjackFlightBookingProvider.normalizeTitle("INFANT", null)).isEqualTo("Master");
        assertThat(TripjackFlightBookingProvider.normalizeTitle("ADULT", "Ms")).isEqualTo("Ms");
        server.verify();
    }
}
