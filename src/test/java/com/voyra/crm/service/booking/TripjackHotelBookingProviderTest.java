package com.voyra.crm.service.booking;

import com.voyra.crm.dto.HotelRoomRequest;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
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
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request and response shapes from Tripjack's Hotel API v3 documentation (review, book, booking-details). */
class TripjackHotelBookingProviderTest {

    private static final String SEARCH = "https://apitest-hms.example.test";
    private static final String BOOKER = "https://apitest-hotel-booker.example.test";
    private static final String OK = "{\"status\":{\"success\":true,\"httpStatus\":200}";

    /** The supplier row every call is given. */
    private static final ResolvedCredential ROW =
            new ResolvedCredential("s1", "Tripjack", SupplierProvider.TRIPJACK, SupplierEnvironment.UAT, "https://apitest.tripjack.com", "test-key", null, null);

    private MockRestServiceServer server;
    private PlatformSettingService settings;
    private TripjackHotelBookingProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        settings = mock(PlatformSettingService.class);
        when(settings.voyraLegal()).thenReturn(new VoyraLegal("Voyra Global Private Limited Travel Services Division", "07ABCDE1234F1Z5", null, "07"));
        provider = new TripjackHotelBookingProvider(builder, settings, SEARCH, "https://hms-search.example.test", BOOKER, "https://hms-booker.example.test");
    }

    private FareReview review(String optionId) {
        return provider.review(optionId, "100012", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4),
                List.of(new HotelRoomRequest(2, List.of(6)), new HotelRoomRequest(1, List.of())), ROW);
    }

    private FareReview review() {
        return review("db35a71a|hash-1|corr-1");
    }

    private static final String REVIEW_OK = OK + ",\"bookingId\":\"TGS208420065548\",\"option\":{\"optionId\":\"db35a71a\","
            + "\"pricing\":{\"totalPrice\":12000.5,\"basePrice\":10000,\"taxes\":1800,\"mf\":180,\"mft\":20.5,\"currency\":\"INR\"},"
            + "\"compliance\":{\"gstType\":\"RESELLER\",\"panRequired\":true,\"passportRequired\":false},"
            + "\"cancellation\":{\"isRefundable\":true,\"deadlineDateTime\":\"2026-11-28T23:59:00\"},\"bookingNotes\":\"Pay city tax at hotel\"}}";

    @Test
    void review_sendsTheOptionItsReviewHashAndCorrelationIdOnTheSearchHostAndMapsTheAnswer() {
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.correlationId").value("corr-1")).andExpect(jsonPath("$.optionId").value("db35a71a"))
                .andExpect(jsonPath("$.reviewHash").value("hash-1")).andExpect(jsonPath("$.hid").value("100012"))
                .andExpect(jsonPath("$.rooms").doesNotExist()).andExpect(jsonPath("$.checkIn").doesNotExist())
                .andRespond(withSuccess(REVIEW_OK, MediaType.APPLICATION_JSON));
        FareReview r = review();
        server.verify();
        assertThat(r.reviewId()).isEqualTo("TGS208420065548");
        assertThat(r.totalFare()).isEqualByComparingTo("12000.50");
        assertThat(r.baseFare()).isEqualByComparingTo("10000"); // basePrice: the management fee is not base
        assertThat(r.cancellationPolicy()).startsWith("Free cancellation until 2026-11-28");
        assertThat(r.freeCancellationUntil()).isEqualTo(Instant.parse("2026-11-28T18:29:00Z"));
        assertThat(r.fareRules()).contains("city tax");
        assertThat(r.compliance()).isEqualTo(new HotelCompliance(true, false, true));
    }

    @Test
    void compliance_readsTheDocumentedFlagsAndTheirShortNames() {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(TripjackHotelBookingProvider.compliance(m.createObjectNode(), m.createObjectNode())).isEqualTo(HotelCompliance.NONE);
        assertThat(TripjackHotelBookingProvider.compliance(m.createObjectNode().put("ipr", true).put("ipm", true),
                m.createObjectNode().set("compliance", m.createObjectNode().put("gstType", "passthrough"))))
                .isEqualTo(new HotelCompliance(true, true, true));
        assertThat(TripjackHotelBookingProvider.compliance(m.createObjectNode(),
                m.createObjectNode().set("compliance", m.createObjectNode().put("gstType", "NA").put("passportRequired", true))))
                .isEqualTo(new HotelCompliance(false, true, false));
    }

    /** An option id from an old screen has no review hash or correlation id: they go as null and Tripjack refuses the review. */
    @Test
    void review_ofAnOldOptionIdSendsNoHashAndARefusalIsFareUnavailable() {
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andExpect(jsonPath("$.optionId").value("opt-1"))
                .andExpect(jsonPath("$.reviewHash").isEmpty())
                .andRespond(withSuccess("{\"status\":{\"success\":false},\"error\":{\"code\":\"OPTION_SOLD_OUT\",\"message\":\"Sold out\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> review("opt-1")).isInstanceOf(FareUnavailableException.class).hasMessage("The hotel could not confirm this rate");
        server.verify();
    }

    @Test
    void review_withoutABookingIdOrTotalIsASupplierException() {
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\" \",\"option\":{\"pricing\":{\"totalPrice\":1}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":null,\"option\":{\"pricing\":{\"totalPrice\":1}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS1\",\"option\":{\"pricing\":{\"totalPrice\":\"12x\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS1\",\"option\":{\"pricing\":{\"totalPrice\":null}}}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(this::review).isInstanceOf(SupplierException.class)
                    .hasMessage("Tripjack hotel review response is missing the booking id or total");
        }
    }

    @Test
    void review_deadlines_blankNotesAndAMissingBase() {
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS2\",\"option\":{\"pricing\":{\"totalPrice\":500},\"bookingNotes\":\" \","
                        + "\"cancellation\":{\"isRefundable\":true,\"penalties\":["
                        + "{\"amount\":0,\"to\":\"2026-11-20T12:00:00Z\"},{\"amount\":0,\"to\":\"\"},{\"amount\":100,\"to\":\"2026-11-25\"}]}}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS3\",\"option\":{\"pricing\":{\"totalPrice\":10},"
                        + "\"cancellation\":{\"isRefundable\":true,\"deadlineDateTime\":\"next week\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS4\",\"option\":{\"pricing\":{\"totalPrice\":10},\"cancellation\":{\"isRefundable\":true}}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH + "/hms/v3/hotel/review")).andRespond(withSuccess(
                OK + ",\"bookingId\":\"TGS5\",\"option\":{\"pricing\":{\"totalPrice\":10}}}", MediaType.APPLICATION_JSON));

        FareReview slabs = review();
        assertThat(slabs.fareRules()).isNull();
        assertThat(slabs.baseFare()).isNull();
        assertThat(slabs.itinerary().path("pricing").path("totalPrice").asInt()).isEqualTo(500);
        assertThat(slabs.freeCancellationUntil()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        FareReview unreadable = review();
        assertThat(unreadable.freeCancellationUntil()).isNull();
        assertThat(unreadable.cancellationPolicy()).isEqualTo("Free cancellation until next week");
        FareReview noDeadline = review();
        assertThat(noDeadline.cancellationPolicy()).isEqualTo("Refundable");
        assertThat(noDeadline.freeCancellationUntil()).isNull();
        assertThat(review().freeCancellationUntil()).isNull();
    }

    // ---------------------------------------------------------------- book

    private static final PaxDetail ASHA = new PaxDetail("ADULT", "Mr", "Asha", "Rao", null, "N1234567", null, "IN", null);
    private static final PaxDetail RAVI = new PaxDetail("CHILD", "Mstr", "Ravi", "Rao", null, " ", null, null, null);
    private static final PaxDetail MEERA = new PaxDetail("ADULT", "ms", "Meera", "Iyer", null, null, null, null, null);
    private static final PaxDetail BABY = new PaxDetail("INFANT", null, "Tara", "Iyer", null, null, null, null, null);

    private BookRequest booking(HotelCompliance compliance, String phone, String dial) {
        return new BookRequest("TGS208420065548", new BigDecimal("12000.50"), List.of(ASHA, RAVI, MEERA, BABY), "a@b.com", phone,
                List.of(List.of(ASHA, RAVI), List.of(MEERA, BABY)), "ABCDE1234F", compliance, dial);
    }

    @Test
    void book_sendsEveryGuestTheTypeContactsAndPaymentOnTheBookerHost() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(method(HttpMethod.POST)).andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.bookingId").value("TGS208420065548")).andExpect(jsonPath("$.type").value("HOTEL"))
                .andExpect(jsonPath("$.paymentInfos[0].amount").value(12000.5))
                .andExpect(jsonPath("$.roomTravellerInfo.length()").value(2))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].ti").value("Mr"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pt").value("ADULT"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pNum").value("N1234567"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[1].ti").value("Master"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[1].pt").value("CHILD"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[1].pan").doesNotExist())
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[1].pNum").doesNotExist())
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[0].ti").value("Ms"))
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[0].pan").doesNotExist())
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[1].ti").value(""))
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[1].pt").value("CHILD"))
                .andExpect(jsonPath("$.deliveryInfo.emails[0]").value("a@b.com"))
                .andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("9876543210"))
                .andExpect(jsonPath("$.deliveryInfo.code[0]").value("+91"))
                .andExpect(jsonPath("$.gstInfo").doesNotExist())
                .andRespond(withSuccess(OK + ",\"bookingId\":\"TJ202487947162\"}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(booking(HotelCompliance.NONE, "+919876543210", null), ROW);
        server.verify();
        assertThat(r.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(r.supplierBookingId()).isEqualTo("TJ202487947162");
    }

    @Test
    void book_aRequiredPanGoesOnEveryGuestAndAResellerRateCarriesVoyrasGst() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book"))
                .andExpect(jsonPath("$.roomTravellerInfo[1].travellerInfo[1].pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.gstInfo.gstNumber").value("07ABCDE1234F1Z5"))
                .andExpect(jsonPath("$.gstInfo.registeredName").value("Voyra Global Private Limited Travel"))
                .andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("501234567"))
                .andExpect(jsonPath("$.deliveryInfo.code[0]").value("+971"))
                .andRespond(withSuccess(OK + ",\"bookingId\":\" \"}", MediaType.APPLICATION_JSON));
        BookResult r = provider.book(booking(new HotelCompliance(true, false, true), "+971 50 123 4567", "971"), ROW);
        server.verify();
        assertThat(r.supplierBookingId()).isEqualTo("TGS208420065548"); // a blank answer id keeps the review's
    }

    @Test
    void book_withoutVoyrasGstDetailsBooksWithoutThem() {
        when(settings.voyraLegal()).thenReturn(new VoyraLegal("Voyra", null, null, null), new VoyraLegal(null, "07ABCDE1234F1Z5", null, "07"),
                new VoyraLegal("Voyra", "07ABCDE1234F1Z5", null, "07"));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(jsonPath("$.gstInfo").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(jsonPath("$.gstInfo").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(jsonPath("$.gstInfo.registeredName").value("Voyra"))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        HotelCompliance gst = new HotelCompliance(false, false, true);
        provider.book(booking(gst, "9876543210", "91"), ROW);
        provider.book(booking(gst, "9876543210", "91"), ROW);
        provider.book(booking(gst, "9876543210", "91"), ROW);
        server.verify();
    }

    @Test
    void book_sparseRequests() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book"))
                .andExpect(jsonPath("$.deliveryInfo.emails").isEmpty()).andExpect(jsonPath("$.deliveryInfo.contacts").isEmpty())
                .andExpect(jsonPath("$.deliveryInfo.code").isEmpty())
                .andExpect(jsonPath("$.roomTravellerInfo.length()").value(1))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].fN").value("Asha"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pan").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book"))
                .andExpect(jsonPath("$.roomTravellerInfo[0].travellerInfo[0].pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("9876543210")).andExpect(jsonPath("$.deliveryInfo.code[0]").value("+91"))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(jsonPath("$.gstInfo").doesNotExist())
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        provider.book(new BookRequest("TGS1", BigDecimal.TEN, List.of(ASHA), null, null, null, null), ROW);
        provider.book(new BookRequest("TGS1", BigDecimal.TEN, List.of(ASHA), "a@b.com", "9876543210", List.of(), "ABCDE1234F", null, " "), ROW);
        provider.book(new BookRequest("TGS1", BigDecimal.TEN, List.of(ASHA), "a@b.com", "9876543210"), ROW);
        server.verify();
        // a number whose "+" code is not the one given is sent whole, never cut
        server.reset();
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andExpect(jsonPath("$.deliveryInfo.contacts[0]").value("442079460000"))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        provider.book(new BookRequest("TGS1", BigDecimal.TEN, List.of(ASHA), "a@b.com", "+44 20 7946 0000", null, null, null, "91"), ROW);
        server.verify();
    }

    @Test
    void book_failedOnlyOnExplicitFalse_otherwiseUnknown() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"error\":{\"code\":\"TRAVELLER_COUNT_MISMATCH\",\"message\":\"2 traveller(s) supplied but the confirmed option requires 3\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Insufficient deposit\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withSuccess("{\"status\":{\"success\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withSuccess("{\"status\":{\"success\":\"no\"}}", MediaType.APPLICATION_JSON));
        assertThat(provider.book(booking(HotelCompliance.NONE, "9876543210", null), ROW).message()).isEqualTo("2 traveller(s) supplied but the confirmed option requires 3");
        BookResult older = provider.book(booking(HotelCompliance.NONE, "9876543210", null), ROW);
        assertThat(older.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(older.message()).isEqualTo("Insufficient deposit");
        assertThat(provider.book(booking(HotelCompliance.NONE, "9876543210", null), ROW).message()).isEqualTo("Booking failed");
        assertThatThrownBy(() -> provider.book(booking(HotelCompliance.NONE, "9876543210", null), ROW)).isInstanceOf(SupplierException.class);
        assertThatThrownBy(() -> provider.book(booking(HotelCompliance.NONE, "9876543210", null), ROW)).isInstanceOf(SupplierException.class);
    }

    @Test
    void book_timeoutIsUnknown_a5xxIsUnknownAndA4xxIsARefusalWithoutEchoingTheBody() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(req -> { throw new SocketTimeoutException("Read timed out"); });
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/book")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errors\":[{\"errCode\":\"77\",\"message\":\"Invalid guest\"}]}"));
        BookRequest r = booking(HotelCompliance.NONE, "9876543210", null);
        assertThatThrownBy(() -> provider.book(r, ROW)).isInstanceOf(SupplierTimeoutException.class);
        assertThatThrownBy(() -> provider.book(r, ROW)).isInstanceOf(SupplierException.class).isNotInstanceOf(SupplierTimeoutException.class);
        BookResult refused = provider.book(r, ROW);
        assertThat(refused.status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(refused.message()).isEqualTo("Supplier refused (HTTP 400) 77: Invalid guest");
    }

    // ---------------------------------------------------------------- booking-details

    @Test
    void bookingDetails_mapsTheDocumentedStatusesConfirmationAndAmount() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andExpect(jsonPath("$.bookingId").value("TJ1"))
                .andRespond(withSuccess(OK + ",\"order\":{\"bookingId\":\"TJ1\",\"amount\":6234.55,\"status\":\"SUCCESS\"},"
                        + "\"hotelConfirmationNumber\":\"HC-778\"}", MediaType.APPLICATION_JSON));
        for (String s : List.of("FAILED", "ABORTED", "CANCELLED", "CANCELLATION_PENDING", "IN_PROGRESS", "ON_HOLD")) {
            server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details"))
                    .andRespond(withSuccess(OK + ",\"order\":{\"status\":\"" + s + "\"},\"hotelConfirmationNumber\":\" \"}", MediaType.APPLICATION_JSON));
        }
        BookResult ok = provider.bookingDetails("TJ1", ROW);
        assertThat(ok.status()).isEqualTo(SupplierBookingStatus.TICKETED);
        assertThat(ok.pnr()).isEqualTo("HC-778");
        assertThat(ok.amount()).isEqualByComparingTo("6234.55");
        assertThat(provider.bookingDetails("TJ1", ROW).status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(provider.bookingDetails("TJ1", ROW).status()).isEqualTo(SupplierBookingStatus.FAILED);
        assertThat(provider.bookingDetails("TJ1", ROW).status()).isEqualTo(SupplierBookingStatus.CANCELLED);
        BookResult cancelling = provider.bookingDetails("TJ1", ROW);
        assertThat(cancelling.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(cancelling.pnr()).isNull();
        assertThat(cancelling.message()).isEqualTo("CANCELLATION_PENDING");
        assertThat(provider.bookingDetails("TJ1", ROW).status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(provider.bookingDetails("TJ1", ROW).status()).isEqualTo(SupplierBookingStatus.PENDING);
        server.verify();
    }

    @Test
    void bookingDetails_anUnsuccessfulBlankOrStatuslessAnswerIsPending() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withSuccess(
                "{\"status\":{\"success\":false},\"error\":{\"message\":\" \"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        BookResult unsuccessful = provider.bookingDetails("TJ1", ROW);
        assertThat(unsuccessful.status()).isEqualTo(SupplierBookingStatus.PENDING);
        assertThat(unsuccessful.message()).isNull();
        assertThat(provider.bookingDetails("TJ1", ROW).message()).isNull();
        BookResult nothing = provider.bookingDetails("TJ1", ROW);
        assertThat(nothing.pnr()).isNull();
        assertThat(nothing.message()).isNull();
        assertThat(nothing.amount()).isNull();
    }

    // ---------------------------------------------------------------- hosts and transport

    /** The key is the given row's; review uses the search host, everything after it the booker host, of that row's environment. */
    @Test
    void prodRowsUseTheProdHosts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer hosts = MockRestServiceServer.bindTo(builder).build();
        TripjackHotelBookingProvider p = new TripjackHotelBookingProvider(builder, settings, SEARCH, "https://hms-search.example.test",
                BOOKER, "https://hms-booker.example.test");
        ResolvedCredential live = new ResolvedCredential("s2", "Tripjack live", SupplierProvider.TRIPJACK, SupplierEnvironment.PROD,
                "https://api.tripjack.com", "live-key", null, null);
        hosts.expect(requestTo("https://hms-search.example.test/hms/v3/hotel/review")).andExpect(header("apikey", "live-key"))
                .andRespond(withSuccess(REVIEW_OK, MediaType.APPLICATION_JSON));
        hosts.expect(requestTo("https://hms-booker.example.test/oms/v3/hotel/booking-details")).andExpect(header("apikey", "live-key"))
                .andRespond(withSuccess(OK + "}", MediaType.APPLICATION_JSON));
        p.review("o|h|c", "1", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), List.of(), live);
        p.bookingDetails("TJ1", live);
        hosts.verify();
        assertThat(p.provider()).isEqualTo(SupplierProvider.TRIPJACK);
        assertThat(p.cancelIsAsync()).isTrue();
    }

    @Test
    void transportFailuresAreClassified() {
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("x".repeat(250)));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("short"));
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(req -> { throw new java.io.IOException("reset"); });
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details"))
                .andRespond(req -> { throw new org.springframework.web.client.RestClientException("converter"); });
        server.expect(requestTo(BOOKER + "/oms/v3/hotel/booking-details")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> provider.bookingDetails("TJ1", ROW)).isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel booking-details call failed (HTTP 502)");
        assertThatThrownBy(() -> provider.bookingDetails("TJ1", ROW)).isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel booking-details call failed (HTTP 502)");
        assertThatThrownBy(() -> provider.bookingDetails("TJ1", ROW)).isInstanceOf(SupplierException.class)
                .isNotInstanceOf(SupplierTimeoutException.class).hasMessage("Tripjack hotel booking-details call failed");
        assertThatThrownBy(() -> provider.bookingDetails("TJ1", ROW)).isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel booking-details call failed");
        assertThatThrownBy(() -> provider.bookingDetails("TJ1", ROW)).isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel booking-details returned an unparseable response");
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
