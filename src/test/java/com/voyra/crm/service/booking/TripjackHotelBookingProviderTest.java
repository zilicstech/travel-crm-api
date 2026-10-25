package com.voyra.crm.service.booking;

import com.voyra.crm.dto.HotelRoomRequest;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Bodies are guessed from the flight shapes and the hotel search response, not real fixtures - unverified until a hotel spike. */
@ExtendWith(MockitoExtension.class)
class TripjackHotelBookingProviderTest {

    private static final String BASE = "https://apitest.tripjack.com";
    private static final String OK = "{\"status\":{\"success\":true,\"httpStatus\":200}";

    @Mock
    private PlatformSupplierCredentialService credentials;

    private MockRestServiceServer server;
    private TripjackHotelBookingProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(credentials.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(
                new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT, BASE, "test-key", null));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TripjackHotelBookingProvider(credentials, builder, BASE, BASE);
    }

    private FareReview review() {
        return provider.review("opt-1", "100012", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4),
                List.of(new HotelRoomRequest(2, List.of(6)), new HotelRoomRequest(1, List.of())));
    }

    private static final String REVIEW_OK = OK + ",\"bookingId\":\"TJH1\",\"option\":{\"pricing\":{\"totalPrice\":12000.5,\"baseRate\":10000,\"currency\":\"INR\"},"
            + "\"cancellation\":{\"isRefundable\":true,\"deadlineDateTime\":\"2026-11-28T23:59:00\"},\"bookingNotes\":\"Pay city tax at hotel\"}}";

    @Test
    void review_sendsOptionAndMapsTotalBasePolicyAndDeadline() {
        server.expect(requestTo(BASE + "/hms/v3/hotel/review")).andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.optionId").value("opt-1")).andExpect(jsonPath("$.hid").value("100012"))
                .andExpect(jsonPath("$.checkIn").value("2026-12-01")).andExpect(jsonPath("$.checkOut").value("2026-12-04"))
                .andExpect(jsonPath("$.roomInfo.length()").value(2))
                .andExpect(jsonPath("$.roomInfo[0].adults").value(2)).andExpect(jsonPath("$.roomInfo[0].children").value(1))
                .andExpect(jsonPath("$.roomInfo[0].childAge[0]").value(6))
                .andExpect(jsonPath("$.roomInfo[1].adults").value(1)).andExpect(jsonPath("$.roomInfo[1].children").doesNotExist())
                .andRespond(withSuccess(REVIEW_OK, MediaType.APPLICATION_JSON));
        FareReview r = review();
        server.verify();
        assertThat(r.reviewId()).isEqualTo("TJH1");
        assertThat(r.totalFare()).isEqualByComparingTo("12000.50");
        assertThat(r.baseFare()).isEqualByComparingTo("10000");
        assertThat(r.cancellationPolicy()).startsWith("Free cancellation until 2026-11-28");
        assertThat(r.freeCancellationUntil()).isNotNull().isBefore(Instant.parse("2026-11-30T00:00:00Z")).isAfter(Instant.parse("2026-11-27T00:00:00Z"));
        assertThat(r.fareRules()).contains("city tax");
    }

    @Test
    void review_missingBaseIsNullNeverZero() {
        server.expect(requestTo(BASE + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJH1\",\"option\":{\"pricing\":{\"totalPrice\":9000}}}", MediaType.APPLICATION_JSON));
        FareReview r = review();
        assertThat(r.baseFare()).isNull();
        assertThat(r.freeCancellationUntil()).isNull();
    }

    @Test
    void review_failureIsFareUnavailableAndMissingIdOrTotalIsSupplierException() {
        server.expect(requestTo(BASE + "/hms/v3/hotel/review")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Room sold out\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/hms/v3/hotel/review")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TJH1\",\"option\":{\"pricing\":{}}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(this::review).isInstanceOf(FareUnavailableException.class).hasMessage("Room sold out");
        assertThatThrownBy(this::review).isInstanceOf(SupplierException.class);
        assertThatThrownBy(this::review).isInstanceOf(SupplierException.class);
    }

    private BookRequest request() {
        PaxDetail lead = new PaxDetail("ADULT", "Mr", "Asha", "Rao", null, null, null, null);
        return new BookRequest("TJH1", new BigDecimal("12000.50"), List.of(lead), "a@b.com", "9999999999");
    }

    @Test
    void book_sendsGuestsAndAmountOnceAndReturnsPending() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookingId").value("TJH1"))
                .andExpect(jsonPath("$.paymentInfos[0].amount").value(12000.5))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].fN").value("Asha"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].ti").value("Mr"))
                .andExpect(jsonPath("$.deliveryInfo.emails[0]").value("a@b.com"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJH1\"}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request());
        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(r.supplierBookingId()).isEqualTo("TJH1");
    }

    @Test
    void book_sendsOneRoomTravellerInfoPerRoomWithTheLeadPanOnTheFirstLead() {
        PaxDetail asha = new PaxDetail("ADULT", "Mr", "Asha", "Rao", null, null, null, null);
        PaxDetail ravi = new PaxDetail("ADULT", null, "Ravi", "Rao", null, null, null, null);
        PaxDetail meera = new PaxDetail("ADULT", "Ms", "Meera", "Iyer", null, null, null, null);
        server.expect(requestTo(BASE + "/oms/v3/hotel/book"))
                .andExpect(jsonPath("$.roomTravellerInfo.length()").value(2))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].fN").value("Asha"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[1].fN").value("Ravi"))
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[0].fN").value("Meera"))
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[0].pan").doesNotExist())
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJH1\"}", MediaType.APPLICATION_JSON));
        provider.book(new BookRequest("TJH1", new BigDecimal("12000.50"), List.of(asha, ravi, meera), "a@b.com", "9999999999",
                List.of(List.of(asha, ravi), List.of(meera)), "ABCDE1234F"));
        server.verify();
    }

    @Test
    void book_failedOnlyOnExplicitFalse_otherwiseUnknown() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Insufficient deposit\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andRespond(withSuccess("{\"status\":{\"success\":\"no\"}}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(request());
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(r.message()).isEqualTo("Insufficient deposit");
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class);
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class);
    }

    @Test
    void book_timeoutIsSupplierTimeoutAndHttpErrorIsPlain() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andRespond(req -> { throw new SocketTimeoutException("Read timed out"); });
        server.expect(requestTo(BASE + "/oms/v3/hotel/book")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierTimeoutException.class);
        assertThatThrownBy(() -> provider.book(request())).isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class);
    }

    @Test
    void bookingDetails_mapsConfirmationAndStatuses() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details")).andExpect(jsonPath("$.bookingId").value("TJH1"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"},\"itemInfos\":{\"HOTEL\":{\"confirmationNumber\":\"HC-778\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"ABORTED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"FAILED\"}}", MediaType.APPLICATION_JSON));
        BookResult ok = provider.bookingDetails("TJH1");
        assertThat(ok.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(ok.pnr()).isEqualTo("HC-778");
        assertThat(provider.bookingDetails("TJH1").status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails("TJH1").status()).isEqualTo(SupplierBookingStatus.FAILED);
    }

    @Test
    void cancel_usesCancelBookingAndUnknownRefundIsNull() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/cancel-booking")).andExpect(jsonPath("$.bookingId").value("TJH1"))
                .andRespond(withSuccess(OK + ",\"totalRefundAmount\":12000}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/cancel-booking")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/cancel-booking")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Past deadline\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/cancel-booking")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThat(provider.cancel("TJH1").refund()).isEqualByComparingTo("12000");
        CancelResult noRefund = provider.cancel("TJH1");
        assertThat(noRefund.accepted()).isTrue();
        assertThat(noRefund.refund()).isNull();
        CancelResult refused = provider.cancel("TJH1");
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.message()).isEqualTo("Past deadline");
        assertThatThrownBy(() -> provider.cancel("TJH1")).isInstanceOf(SupplierException.class);
    }

    /** The quote never calls cancel-booking (a guessed dry-run flag could really cancel): it reads booking-details. */
    @Test
    void cancelQuote_readsBookingDetailsAndMissingRefundIsNull() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"},\"cancellation\":{\"refundAmount\":8000}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details")).andRespond(withSuccess(OK + ",\"order\":{\"status\":\"SUCCESS\"}}", MediaType.APPLICATION_JSON));
        assertThat(provider.cancelQuote("TJH1").refund()).isEqualByComparingTo("8000");
        assertThat(provider.cancelQuote("TJH1").refund()).isNull();
        server.verify();
    }

    /** A refund is quoted only for a booking the supplier still shows as confirmed; anything else goes to Voyra support (null). */
    @Test
    void cancelQuote_onABookingNoLongerConfirmedAtTheSupplierIsUnknown() {
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details"))
                .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"CANCELLED\"},\"cancellation\":{\"refundAmount\":8000}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/oms/v3/hotel/booking-details"))
                .andRespond(withSuccess(OK + ",\"cancellation\":{\"refundAmount\":8000}}", MediaType.APPLICATION_JSON));
        CancelQuote cancelled = provider.cancelQuote("TJH1");
        assertThat(cancelled.refund()).isNull();
        assertThat(cancelled.message()).contains("CANCELLED");
        assertThat(provider.cancelQuote("TJH1").refund()).isNull();
        server.verify();
    }

    @Test
    void restClientBuilderQualifierReachesTheConstructor() {
        var ctor = TripjackHotelBookingProvider.class.getDeclaredConstructors()[0];
        var q = java.util.Arrays.stream(ctor.getParameters()).filter(p -> p.getType() == RestClient.Builder.class)
                .findFirst().orElseThrow().getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class);
        assertThat(q).isNotNull();
        assertThat(q.value()).isEqualTo("tripjackRestClientBuilder");
    }
}
