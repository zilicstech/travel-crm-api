package com.voyra.crm.service.tripjack;

import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Search, hotel page, thumbnails and the HTTP error paths, all answered offline by MockRestServiceServer. */
@ExtendWith(MockitoExtension.class)
class TripjackHotelSearchProviderCoverageTest {

    private static final String UAT = "https://apitest-hms.tripjack.com";
    private static final String PROD = "https://hms-search.tripjack.com";

    @Mock
    private PlatformSupplierCredentialService credentials;

    private MockRestServiceServer server;
    private TripjackHotelSearchProvider provider;
    private final MovableClock clock = new MovableClock();

    /** A clock a test moves forward to age the caches. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private void setUp(SupplierEnvironment env, boolean ordered) {
        when(credentials.resolveActive(SupplierProvider.TRIPJACK))
                .thenReturn(new ResolvedCredential(SupplierProvider.TRIPJACK, env, "https://unused.example.test", "test-key", null));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(!ordered).build();
        provider = new TripjackHotelSearchProvider(credentials, builder, UAT, PROD, clock);
    }

    private void json(String host, String path, String body) {
        server.expect(requestTo(host + path)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void staticData(String host, String nationalities) {
        json(host, "/hms/v3/content/fetch-city-regionIds?limit=2000", """
                {"hotelCityRegionIds":[{"cityName":"Goa","cityRegionId":11,"countryName":"India"},{"cityName":" ","cityRegionId":12}],
                 "hasMore":true,"nextCursor":" "}""");
        json(host, "/hms/v3/nationality-info", nationalities);
    }

    private void expectUatStaticData() {
        staticData(UAT, "{\"nationalityInfos\":[{\"countryId\":\"106\",\"code\":\"in\"}]}");
    }

    private static HotelSearchQuery query(String city, String country) {
        HotelSearchQuery q = new HotelSearchQuery();
        q.setCity(city);
        q.setCountryCode(country);
        q.setCheckIn(LocalDate.of(2026, 12, 1));
        q.setCheckOut(LocalDate.of(2026, 12, 3));
        q.setRooms(1);
        q.setAdults(2);
        q.setChildren(0);
        return q;
    }

    private static String option(String id, int price) {
        return "{\"optionId\":\"" + id + "\",\"pricing\":{\"totalPrice\":" + price + "}}";
    }

    @Test
    void aCityWithNoMappedHotelsFindsNothingAndTheEmptyMappingIsCached() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        server.expect(ExpectedCount.once(), requestTo(UAT + "/hms/v3/content/fetch-hotel-mapping"))
                .andRespond(withSuccess("{\"hotels\":[{\"tjHotelId\":\" \"}]}", MediaType.APPLICATION_JSON));

        provider.refreshStaticData();
        assertThat(provider.search(query("Goa", "FR"))).isEmpty();
        assertThat(provider.search(query("goa", null))).isEmpty();
        server.verify();
    }

    @Test
    void aSupplierWithoutIndianNationalityIsRefused() {
        setUp(SupplierEnvironment.UAT, true);
        staticData(UAT, "{\"nationalityInfos\":[{\"countryId\":\"1\",\"code\":\"FR\"}]}");
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        provider.refreshStaticData();

        assertThatThrownBy(() -> provider.search(query("Goa", "IN")))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack hotels don't support nationality IN");
    }

    @Test
    void failedListingBatchesKeepTheOthersResultsAndAtMostThreeBatchesGoOut() {
        setUp(SupplierEnvironment.UAT, false);
        expectUatStaticData();
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 301; i++) ids.append(i > 1 ? "," : "").append("{\"tjHotelId\":\"").append(i).append("\"}");
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[" + ids + "]}");
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing")).andExpect(jsonPath("$.hids[0]").value(1))
                .andRespond(withSuccess("{\"hotels\":[{\"tjHotelId\":\"1\",\"name\":\"B\",\"options\":[" + option("B1", 900) + "]},"
                        + "{\"tjHotelId\":\"2\",\"name\":\"A\",\"options\":[" + option("A1", 500) + "]},"
                        + "{\"tjHotelId\":\"3\",\"options\":[]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing")).andExpect(jsonPath("$.hids[0]").value(101))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing")).andExpect(jsonPath("$.hids[0]").value(201))
                .andExpect(jsonPath("$.hids.length()").value(100))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        provider.refreshStaticData();

        List<HotelOfferResponse> offers = provider.search(query("Goa", "IN"));

        server.verify();
        assertThat(offers).extracting(HotelOfferResponse::getOfferId).containsExactly("A1", "B1");
        assertThat(offers).allSatisfy(o -> assertThat(o.getNights()).isEqualTo(2));
    }

    @Test
    void aSecondSearchReusesTheHotelIdsAndListsAgain() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        String listing = "{\"hotels\":[{\"tjHotelId\":\"5\",\"name\":\"H\",\"options\":[" + option("O5", 100) + "]}]}";
        json(UAT, "/hms/v3/hotel/listing", listing);
        json(UAT, "/hms/v3/hotel/listing", listing);
        provider.refreshStaticData();

        assertThat(provider.search(query("Goa", " "))).extracting(HotelOfferResponse::getOfferId).containsExactly("O5");
        assertThat(provider.search(query("Goa", "IN"))).extracting(HotelOfferResponse::getOfferId).containsExactly("O5");
        server.verify();
    }

    @Test
    void aListingBatchThatFailsForAnotherReasonIsAGenericSearchFailure() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing")).andRespond(request -> {
            throw new IllegalStateException("boom");
        });
        provider.refreshStaticData();

        assertThatThrownBy(() -> provider.search(query("Goa", "IN")))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack hotel search failed")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCityListStopsAfterFiveHundredPages() {
        setUp(SupplierEnvironment.UAT, true);
        String page = "{\"hotelCityRegionIds\":[{\"cityName\":\"Goa\",\"cityRegionId\":11,\"countryName\":\"India\"}],"
                + "\"hasMore\":true,\"nextCursor\":\"P\"}";
        json(UAT, "/hms/v3/content/fetch-city-regionIds?limit=2000", page);
        server.expect(ExpectedCount.times(499), requestTo(UAT + "/hms/v3/content/fetch-city-regionIds?limit=2000&cursor=P"))
                .andRespond(withSuccess(page, MediaType.APPLICATION_JSON));
        json(UAT, "/hms/v3/nationality-info", "{\"nationalityInfos\":[]}");

        provider.refreshStaticData();

        server.verify();
    }

    @Test
    void whenEveryListingBatchFailsTheFirstErrorIsThrown() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Upstream down\"}}"));
        provider.refreshStaticData();

        assertThatThrownBy(() -> provider.search(query("Goa", "IN")))
                .isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel search failed (/hms/v3/hotel/listing, HTTP 502): Upstream down");
    }

    @Test
    void anEmptyListingFindsNothing() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        json(UAT, "/hms/v3/hotel/listing", "  ");
        provider.refreshStaticData();

        assertThat(provider.search(query("Goa", "IN"))).isEmpty();
    }

    @Test
    void theStaticDataIsLoadedInTheBackgroundOnceAndASearchRetriesUntilItIsThere() throws Exception {
        setUp(SupplierEnvironment.UAT, true);
        CountDownLatch release = new CountDownLatch(1);
        server.expect(requestTo(UAT + "/hms/v3/content/fetch-city-regionIds?limit=2000")).andRespond(request -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            MockClientHttpResponse r = new MockClientHttpResponse(
                    "{\"hotelCityRegionIds\":[{\"cityName\":\"Goa\",\"cityRegionId\":11,\"countryName\":\"India\"}]}".getBytes(), HttpStatus.OK);
            r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return r;
        });
        json(UAT, "/hms/v3/nationality-info", "{\"nationalityInfos\":[{\"countryId\":\"106\",\"code\":\"IN\"}]}");
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[]}");

        assertThatThrownBy(() -> provider.search(query("Goa", "IN"))).hasMessageContaining("still loading");
        // While the first load runs, neither another search nor the scheduled refresh starts a second one.
        assertThatThrownBy(() -> provider.search(query("Goa", "IN"))).hasMessageContaining("still loading");
        provider.refreshStaticData();
        release.countDown();

        List<HotelOfferResponse> offers = null;
        for (int i = 0; i < 100 && offers == null; i++) {
            try {
                offers = provider.search(query("Goa", "IN"));
            } catch (IllegalStateException stillLoading) {
                Thread.sleep(50);
            }
        }
        assertThat(offers).isEmpty();
        server.verify();
    }

    @Test
    void theHotelPageUsesTheSearchsCorrelationIdAndCachesTheStaticDetail() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        String pricing = "{\"hotelName\":\"Priced Hotel\",\"options\":[" + option("O1", 700) + "]}";
        server.expect(requestTo(UAT + "/hms/v3/hotel/pricing")).andExpect(jsonPath("$.correlationId").value("corr-9"))
                .andExpect(jsonPath("$.hid").value("H1")).andExpect(jsonPath("$.nationality").value("106"))
                .andRespond(withSuccess(pricing, MediaType.APPLICATION_JSON));
        json(UAT, "/hms/v3/hotel/static-detail", "{\"name\":\"Static\",\"star_rating\":5}");
        server.expect(requestTo(UAT + "/hms/v3/hotel/pricing")).andRespond(withSuccess(pricing, MediaType.APPLICATION_JSON));
        HotelSearchQuery q = query("Goa", "IN");
        q.setCorrelationId("corr-9");
        provider.refreshStaticData();

        HotelDetailResponse first = provider.detail("H1", q);
        q.setCorrelationId(" ");
        HotelDetailResponse second = provider.detail("H1", q);

        server.verify();
        assertThat(first.getName()).isEqualTo("Priced Hotel");
        assertThat(first.getStarRating()).isEqualTo(5);
        assertThat(second.getStarRating()).isEqualTo(5);
        assertThat(first.getOptions()).extracting(HotelDetailResponse.RoomOption::getOptionId).containsExactly("O1");
    }

    @Test
    void withoutStaticDetailTheHotelPageFallsBackToTheContentCall() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        server.expect(requestTo(UAT + "/hms/v3/hotel/pricing")).andExpect(jsonPath("$.correlationId").exists())
                .andRespond(withSuccess("{\"options\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UAT + "/hms/v3/hotel/static-detail")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        json(UAT, "/hms/v3/content/fetch-hotel-content", "{\"hotels\":[{\"tjHotelId\":\"H1\",\"name\":\"From Content\",\"star_rating\":3}]}");
        provider.refreshStaticData();

        HotelDetailResponse d = provider.detail("H1", query("Goa", "IN"));

        server.verify();
        assertThat(d.getName()).isEqualTo("From Content");
        assertThat(d.getStarRating()).isEqualTo(3);
        assertThat(d.getOptions()).isEmpty();
    }

    @Test
    void prodThumbnailsSkipBlankIdsAndIdlessHotelsAndSurviveAFailedBatch() {
        setUp(SupplierEnvironment.PROD, false);
        server.expect(requestTo(PROD + "/hms/v3/content/fetch-hotel-content")).andExpect(jsonPath("$.hotelIds[0]").value("1"))
                .andRespond(withSuccess("{\"hotels\":[{\"tjHotelId\":\"1\",\"star_rating\":2},{\"name\":\"No id\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROD + "/hms/v3/content/fetch-hotel-content")).andExpect(jsonPath("$.hotelIds[0]").value("6"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var thumbs = provider.thumbnails(List.of("1", " ", "2", "3", "4", "5", "6"));

        server.verify();
        assertThat(thumbs).singleElement().satisfies(t -> {
            assertThat(t.getHotelId()).isEqualTo("1");
            assertThat(t.getStarRating()).isEqualTo(2);
        });
    }

    @Test
    void anUnreachableTripjackIsReportedPlainly() {
        setUp(SupplierEnvironment.UAT, true);
        server.expect(requestTo(UAT + "/hms/v3/content/fetch-city-regionIds?limit=2000")).andExpect(method(HttpMethod.GET))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(provider::refreshStaticData)
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack hotel search could not reach Tripjack");
    }

    @Test
    void anUnparseableResponseIsAnError() {
        setUp(SupplierEnvironment.UAT, true);
        json(UAT, "/hms/v3/content/fetch-city-regionIds?limit=2000", "not json");

        assertThatThrownBy(provider::refreshStaticData).isInstanceOf(SupplierException.class)
                .hasMessage("Tripjack hotel /hms/v3/content/fetch-city-regionIds returned an unparseable response");
    }

    @Test
    void aBodyThatSaysItFailedIsAnErrorWithItsMessage() {
        setUp(SupplierEnvironment.UAT, true);
        json(UAT, "/hms/v3/content/fetch-city-regionIds?limit=2000", "{\"status\":{\"success\":false},\"errors\":[{\"message\":\"Bad key\"}]}");

        assertThatThrownBy(provider::refreshStaticData)
                .hasMessage("Tripjack hotel search returned an error: Bad key");
    }

    @Test
    void anErrorObjectOrErrorListAloneAlsoFails() {
        setUp(SupplierEnvironment.UAT, true);
        json(UAT, "/hms/v3/content/fetch-city-regionIds?limit=2000", "{\"status\":{\"success\":true},\"error\":{}}");
        json(UAT, "/hms/v3/content/fetch-city-regionIds?limit=2000", "{\"errors\":[{}]}");

        assertThatThrownBy(provider::refreshStaticData).hasMessage("Tripjack hotel search returned an error");
        assertThatThrownBy(provider::refreshStaticData).hasMessage("Tripjack hotel search returned an error");
    }

    @Test
    void anHttpErrorWithANonJsonBodyCarriesNoMessage() {
        setUp(SupplierEnvironment.UAT, true);
        server.expect(requestTo(UAT + "/hms/v3/content/fetch-city-regionIds?limit=2000"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("<html>nope</html>"));

        assertThatThrownBy(provider::refreshStaticData)
                .hasMessage("Tripjack hotel search failed (/hms/v3/content/fetch-city-regionIds, HTTP 403)");
    }

    @Test
    void theCityListIsReloadedOnceItIsAWeekOld() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        expectUatStaticData();

        provider.refreshStaticData();
        clock.advance(Duration.ofDays(7).minusSeconds(1));
        provider.refreshStaticData(); // still under a week: no call
        clock.advance(Duration.ofSeconds(1));
        provider.refreshStaticData();

        server.verify();
    }

    @Test
    void theHotelIdsOfACityAreFetchedAgainAfterADay() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        String listing = "{\"hotels\":[{\"tjHotelId\":\"5\",\"name\":\"H\",\"options\":[" + option("O5", 100) + "]}]}";
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        json(UAT, "/hms/v3/hotel/listing", listing);
        json(UAT, "/hms/v3/content/fetch-hotel-mapping", "{\"hotels\":[{\"tjHotelId\":\"5\"}]}");
        json(UAT, "/hms/v3/hotel/listing", listing);
        provider.refreshStaticData();

        provider.search(query("Goa", "IN"));
        clock.advance(Duration.ofDays(1));
        assertThat(provider.search(query("Goa", "IN"))).extracting(HotelOfferResponse::getOfferId).containsExactly("O5");

        server.verify();
    }

    @Test
    void hotelContentIsKeptAWeekThenFetchedAgain() {
        setUp(SupplierEnvironment.PROD, true);
        json(PROD, "/hms/v3/content/fetch-hotel-content", "{\"hotels\":[{\"tjHotelId\":\"1\",\"star_rating\":2}]}");
        json(PROD, "/hms/v3/content/fetch-hotel-content", "{\"hotels\":[{\"tjHotelId\":\"1\",\"star_rating\":4}]}");

        assertThat(provider.thumbnails(List.of("1"))).singleElement().satisfies(t -> assertThat(t.getStarRating()).isEqualTo(2));
        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertThat(provider.thumbnails(List.of("1"))).singleElement().satisfies(t -> assertThat(t.getStarRating()).isEqualTo(2));
        clock.advance(Duration.ofSeconds(1));
        assertThat(provider.thumbnails(List.of("1"))).singleElement().satisfies(t -> assertThat(t.getStarRating()).isEqualTo(4));

        server.verify();
    }

    @Test
    void theStaticDetailIsKeptADayThenFetchedAgain() {
        setUp(SupplierEnvironment.UAT, true);
        expectUatStaticData();
        String pricing = "{\"hotelName\":\"Priced Hotel\",\"options\":[" + option("O1", 700) + "]}";
        json(UAT, "/hms/v3/hotel/pricing", pricing);
        json(UAT, "/hms/v3/hotel/static-detail", "{\"star_rating\":5}");
        json(UAT, "/hms/v3/hotel/pricing", pricing);
        json(UAT, "/hms/v3/hotel/static-detail", "{\"star_rating\":3}");
        provider.refreshStaticData();

        assertThat(provider.detail("H1", query("Goa", "IN")).getStarRating()).isEqualTo(5);
        clock.advance(Duration.ofDays(1));
        assertThat(provider.detail("H1", query("Goa", "IN")).getStarRating()).isEqualTo(3);

        server.verify();
    }

    @Test
    void aNullHotelIdIsSkippedLikeABlankOne() {
        setUp(SupplierEnvironment.PROD, true);
        json(PROD, "/hms/v3/content/fetch-hotel-content", "{\"hotels\":[{\"tjHotelId\":\"1\",\"star_rating\":2}]}");

        var thumbs = provider.thumbnails(java.util.Arrays.asList(null, "1"));

        server.verify();
        assertThat(thumbs).extracting(com.voyra.crm.dto.HotelThumbnailResponse::getHotelId).containsExactly("1");
    }

    @Test
    void anEmptyContentAnswerListsNoThumbnails() {
        setUp(SupplierEnvironment.PROD, true);
        server.expect(requestTo(PROD + "/hms/v3/content/fetch-hotel-content")).andRespond(withSuccess());

        assertThat(provider.thumbnails(List.of("1"))).isEmpty();
        server.verify();
    }
}
