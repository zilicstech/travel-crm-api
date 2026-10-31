package com.voyra.crm.service.booking;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.FareUnavailableException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.exception.SupplierTimeoutException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.*;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Bodies are built from the spec section 7.3 table, not real fixtures - unverified until the slice 0 spike. */
@ExtendWith(MockitoExtension.class)
class TripjackFlightBookingProviderTest {

    private static final String BASE = "https://apitest.tripjack.com";

    @Mock
    private PlatformSupplierCredentialService credentials;

    private MockRestServiceServer server;
    private TripjackFlightBookingProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(credentials.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(
                new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT, BASE, "test-key", null));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TripjackFlightBookingProvider(credentials, builder);
    }

    private static final String OK = "{\"status\":{\"success\":true,\"httpStatus\":200}";

    @Test
    void review_sendsPriceIdAndMapsFareAndRules() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.priceIds[0]").value("off-1"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJS1\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":9000.5,\"BF\":7000}}},\"tripInfos\":[{\"x\":1}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andExpect(jsonPath("$.id").value("TJS1"))
                .andRespond(withSuccess(OK + ",\"fareRule\":{\"cancel\":\"Rs 3000\"}}", MediaType.APPLICATION_JSON));

        FareReview r = provider.review("off-1", 1, 0, 0);

        server.verify();
        assertThat(r.reviewId()).isEqualTo("TJS1");
        assertThat(r.totalFare()).isEqualByComparingTo("9000.50");
        assertThat(r.baseFare()).isEqualByComparingTo("7000");
        assertThat(r.fareRules()).contains("Rs 3000");
        assertThat(r.itinerary().isArray()).isTrue();
    }

    @Test
    void review_failureIsFareUnavailable() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Fare expired\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0))
                .isInstanceOf(FareUnavailableException.class).hasMessage("The airline could not confirm this fare"); // never the supplier's text
    }

    @Test
    void review_missingFieldsDoNotBlowUp() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0)).isInstanceOf(SupplierException.class);
    }

    private BookRequest request() {
        PaxDetail pax = new PaxDetail("ADULT", "Mr", "Asha", "Rao", LocalDate.of(1990, 1, 2), "P1234567", LocalDate.of(2030, 1, 1), "IN");
        return new BookRequest("TJS1", new BigDecimal("9000.50"), List.of(pax), "a@b.com", "9999999999");
    }

    @Test
    void book_sendsSpecBodyAndReturnsPending() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookingId").value("TJS1"))
                .andExpect(jsonPath("$.paymentInfos[0].amount").value(9000.5))
                .andExpect(jsonPath("$.travellerInfo[0].fN").value("Asha"))
                .andExpect(jsonPath("$.travellerInfo[0].pt").value("ADULT"))
                .andExpect(jsonPath("$.deliveryInfo.emails[0]").value("a@b.com"))
                .andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("9999999999"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJS1\"}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request());
        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(r.supplierBookingId()).isEqualTo("TJS1");
    }

    @Test
    void book_successFalseIsFailedWithMessage() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Insufficient supplier deposit\"}]}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request());
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(r.message()).isEqualTo("Insufficient supplier deposit");
    }

    @Test
    void emptyOkBodyOnBookIsUnknownNotFailed() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withSuccess("{\"status\":{\"success\":\"no\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class);
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class);
    }

    /** Live 2026-10-30: Tripjack answers a sold-out fare's review with HTTP 400 errCode 1000; the agent gets "pick another fare", not a 502. */
    @Test
    void a4xxOnReviewIsAnUnavailableFareNotAnUnreachableSupplier() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":{\"success\":false,\"httpStatus\":400},\"errors\":[{\"errCode\":\"1000\","
                        + "\"message\":\"Requested flight is no longer available. Please try different flight\"}]}"));
        assertThatThrownBy(() -> provider.review(List.of("P1"), 1, 0, 0)).isInstanceOf(FareUnavailableException.class)
                .hasMessageContaining("pick another fare");
    }

    /** Live 2026-10-30: a 400 on book left no order at Tripjack, so it is a clean failure that releases the hold at once. */
    @Test
    void a4xxOnBookIsAFailureWithTheSuppliersCodeAndA5xxStaysUnknown() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":{\"success\":false},\"errors\":[{\"errCode\":\"1024\",\"message\":\"Invalid title\"}]}"));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        BookResult refused = provider.book(request());
        assertThat(refused.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(refused.message()).contains("1024").contains("Invalid title");
        assertThat(provider.book(request()).status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class);
    }

    @Test
    void emptyOkBodyOnCancelIsUnknownNotRefused() {
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/submit-amendment")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/submit-amendment")).andRespond(withSuccess("{\"status\":{\"success\":\"no\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.cancel("TJS1")).isInstanceOf(SupplierException.class);
        assertThatThrownBy(() -> provider.cancel("TJS1")).isInstanceOf(SupplierException.class);
    }

    @Test
    void cancel_successFalseIsRefusedWithMessage() {
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/submit-amendment")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Flown sector\"}]}", MediaType.APPLICATION_JSON));
        CancelResult c = provider.cancel("TJS1");
        assertThat(c.accepted()).isFalse();
        assertThat(c.message()).isEqualTo("Flown sector");
    }

    @Test
    void unknownOrderStatusIsPendingAndMissingRefundIsNull() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"ABORTED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/amendment-charges")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        assertThat(provider.bookingDetails("TJS1").status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.cancelQuote("TJS1").refund()).isNull();
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
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierTimeoutException.class);
    }

    @Test
    void book_httpErrorIsPlainSupplierException() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> provider.book(request()))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class);
    }

    @Test
    void bookingDetails_mapsTicketedPnrAndTickets() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andExpect(jsonPath("$.bookingId").value("TJS1"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"},\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                        + "{\"pnrDetails\":{\"BLR-HKT\":\"ABC123\"},\"ticketNumberDetails\":{\"BLR-HKT\":\"176-111\"}},"
                        + "{\"pnrDetails\":{\"BLR-HKT\":\"ABC123\"},\"ticketNumberDetails\":{\"BLR-HKT\":\"176-222\"}}]}}}",
                        MediaType.APPLICATION_JSON));
        BookResult r = provider.bookingDetails("TJS1");
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(r.pnr()).isEqualTo("ABC123");
        assertThat(r.ticketNumbers()).containsExactly("176-111", "176-222");
    }

    @Test
    void bookingDetails_statusMappingAndMissingFields() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"PENDING\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"FAILED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"CANCELLED\"}}", MediaType.APPLICATION_JSON));
        BookResult pending = provider.bookingDetails("TJS1");
        assertThat(pending.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(pending.pnr()).isNull();
        assertThat(pending.ticketNumbers()).isEmpty();
        assertThat(provider.bookingDetails("TJS1").status()).isEqualTo(SupplierBookingStatus.FAILED);
        // a cancelled booking is how an accepted amendment is confirmed - not a failed booking
        assertThat(provider.bookingDetails("TJS1").status()).isEqualTo(SupplierBookingStatus.CANCELLED);
    }

    @Test
    void cancelQuoteAndCancel_useAmendmentEndpoints() {
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/amendment-charges"))
                .andExpect(jsonPath("$.bookingId").value("TJS1")).andExpect(jsonPath("$.type").value("CANCELLATION"))
                .andRespond(withSuccess(OK + ",\"totalRefundAmount\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/submit-amendment"))
                .andRespond(withSuccess(OK + ",\"amendmentId\":\"A1\",\"totalRefundAmount\":7200}", MediaType.APPLICATION_JSON));
        assertThat(provider.cancelQuote("TJS1").refund()).isEqualByComparingTo("7200");
        CancelResult c = provider.cancel("TJS1");
        assertThat(c.accepted()).isTrue();
        assertThat(c.refund()).isEqualByComparingTo("7200");
        server.verify();
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

        FareReview r = provider.review(List.of("ON-6E2031-PUB", "RT-6E2032-PUB"), 1, 0, 0);

        server.verify();
        assertThat(r.reviewId()).isEqualTo("TJS101900012345");
        assertThat(r.totalFare()).isEqualByComparingTo("10700");
        assertThat(r.itinerary().findValues("sI")).hasSize(2);
    }

    @Test
    void aRefusedPairGetsItsOwnMessage() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Invalid combination\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.review(List.of("a", "b"), 1, 0, 0))
                .isInstanceOf(FareUnavailableException.class).hasMessage(AgencySupplierMessage.NOT_CONFIRMED_PAIR);
    }

    @Test
    void bookingDetails_keepsEveryDistinctSectorPnr() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(
                OK + ",\"order\":{\"status\":\"SUCCESS\"},\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                        + "{\"pnrDetails\":{\"DEL-BOM\":\"ABC123\",\"BOM-DEL\":\"XYZ789\"}},"
                        + "{\"pnrDetails\":{\"DEL-BOM\":\"ABC123\",\"BOM-DEL\":\"XYZ789\"}}]}}}",
                MediaType.APPLICATION_JSON));
        assertThat(provider.bookingDetails("TJS1").pnr()).isEqualTo("ABC123 / XYZ789");
    }

    // ---------------------------------------------------------------- coverage: review edges, sparse bookings, odd answers

    @Test
    void aReviewThatCannotReachTheSupplierIsNotAnUnavailableFare() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(FareUnavailableException.class)
                .hasMessage("Tripjack review call failed (HTTP 503)");
    }

    @Test
    void aRefused4xxPairReviewGetsThePairMessage() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> provider.review(List.of("ON", "RET"), 1, 0, 0))
                .isInstanceOf(FareUnavailableException.class).hasMessage(AgencySupplierMessage.NOT_CONFIRMED_PAIR);
    }

    @Test
    void aReviewWithABlankIdOrUnreadableTotalIsIncomplete() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\" \",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS1\",\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":\"abc\"}}}}", MediaType.APPLICATION_JSON));

        String message = "Tripjack review response is missing the review id or total fare";
        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0)).isInstanceOf(SupplierException.class).hasMessage(message);
        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0)).isInstanceOf(SupplierException.class).hasMessage(message);
    }

    @Test
    void aReviewWithoutTripInfosBaseOrRulesStillReviews() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJS2\",\"conditions\":{\"x\":1},\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100,\"BF\":null}}}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/fms/v2/farerule")).andRespond(withSuccess(OK + ",\"fareRule\":null}", MediaType.APPLICATION_JSON));

        FareReview r = provider.review("off-1", 1, 0, 0);

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

        assertThat(provider.review("off-1", 1, 0, 0).fareRules()).isNull();
    }

    @Test
    void aReviewWithANullBookingIdIsIncomplete() {
        server.expect(requestTo(BASE + "/fms/v1/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":null,\"totalPriceInfo\":{\"totalFareDetail\":{\"fC\":{\"TF\":100}}}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.review("off-1", 1, 0, 0))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack review response is missing the review id or total fare");
    }

    @Test
    void aTimeoutWithItsOwnCauseIsStillATimeout() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(req -> {
            SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
            timeout.initCause(new java.io.IOException("socket"));
            throw timeout;
        });

        assertThatThrownBy(() -> provider.bookingDetails("TJS1"))
                .isInstanceOf(SupplierTimeoutException.class).hasMessage("Tripjack booking-details timed out");
    }

    @Test
    void aBookingWithoutContactsOrOptionalPassengerFieldsSendsEmptyLists() {
        PaxDetail bare = new PaxDetail("CHILD", "Ms", "Pax", "Child", null, null, null, null);
        server.expect(requestTo(BASE + "/oms/v1/air/book"))
                .andExpect(jsonPath("$.deliveryInfo.emails").isEmpty())
                .andExpect(jsonPath("$.deliveryInfo.contacts").isEmpty())
                .andExpect(jsonPath("$.travellerInfo[0].dob").doesNotExist())
                .andExpect(jsonPath("$.travellerInfo[0].pNum").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        BookResult r = provider.book(new BookRequest("TJS1", new BigDecimal("100"), List.of(bare), null, null));

        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
    }

    @Test
    void aBlankEmailIsSentAsGiven() {
        server.expect(requestTo(BASE + "/oms/v1/air/book")).andExpect(jsonPath("$.deliveryInfo.emails[0]").value(" "))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));

        assertThat(provider.book(new BookRequest("TJS1", BigDecimal.ONE, List.of(), " ", "+91 00")).status())
                .isEqualTo(SupplierBookingStatus.PENDING);
    }

    @Test
    void anUnsuccessfulBookingDetailsAnswerIsStillPending() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Try later\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        BookResult first = provider.bookingDetails("TJS1");
        BookResult second = provider.bookingDetails("TJS1");

        assertThat(first.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(first.message()).isEqualTo("Try later");
        assertThat(second.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(second.message()).isNull();
    }

    @Test
    void blankPnrsAndTicketsAreSkippedAndAMissingStatusHasNoMessage() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess(OK + ",\"itemInfos\":{\"AIR\":{\"travellerInfos\":["
                + "{\"pnrDetails\":{\"DEL-BOM\":\" \"},\"ticketNumberDetails\":{\"DEL-BOM\":\"\"}}]}}}", MediaType.APPLICATION_JSON));

        BookResult r = provider.bookingDetails("TJS1");

        assertThat(r.pnr()).isNull();
        assertThat(r.ticketNumbers()).isEmpty();
        assertThat(r.message()).isNull();
    }

    @Test
    void anEmptyCancelQuoteHasNoRefundOrMessage() {
        server.expect(requestTo(BASE + "/oms/v1/air/amendment/amendment-charges")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        CancelQuote q = provider.cancelQuote("TJS1");

        assertThat(q.refund()).isNull();
        assertThat(q.message()).isNull();
    }

    @Test
    void aTransportFailureThatIsNotATimeoutIsAPlainFailureAndJunkIsUnparseable() {
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(req -> { throw new java.io.IOException("reset"); });
        server.expect(requestTo(BASE + "/oms/v1/booking-details")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.bookingDetails("TJS1"))
                .isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class)
                .hasMessage("Tripjack booking-details call failed");
        assertThatThrownBy(() -> provider.bookingDetails("TJS1"))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack booking-details returned an unparseable response");
    }
}
