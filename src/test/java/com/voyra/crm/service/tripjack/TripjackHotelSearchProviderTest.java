package com.voyra.crm.service.tripjack;

import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Offline: every Tripjack call is answered by MockRestServiceServer with the v3 shapes. */
@ExtendWith(MockitoExtension.class)
class TripjackHotelSearchProviderTest {

    private static final String HOST = "https://apitest-hms.tripjack.com";

    @Mock
    private PlatformSupplierCredentialService platformSupplierCredentialService;

    private MockRestServiceServer server;
    private TripjackHotelSearchProvider provider;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(platformSupplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT,
                "https://apitest.tripjack.com", "test-key", null));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TripjackHotelSearchProvider(platformSupplierCredentialService, builder, HOST, "https://hms-search.tripjack.com");
    }

    private HotelSearchQuery query(String city, String country) {
        HotelSearchQuery q = new HotelSearchQuery();
        q.setCity(city);
        q.setCountryCode(country);
        q.setCheckIn(LocalDate.of(2026, 12, 1));
        q.setCheckOut(LocalDate.of(2026, 12, 4));
        q.setRooms(1);
        q.setAdults(2);
        q.setChildren(1);
        return q;
    }

    private void json(String path, HttpMethod m, String body) {
        server.expect(requestTo(HOST + path)).andExpect(method(m)).andExpect(header("apikey", "test-key"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void cityList() {
        json("/hms/v3/content/fetch-city-regionIds?limit=2000", HttpMethod.GET, """
                {"hotelCityRegionIds":[{"cityName":"Goa","cityRegionId":11,"countryName":"India"},
                                       {"cityName":"Paris","cityRegionId":21,"countryName":"France"}],
                 "hasMore":true,"nextCursor":"P2"}""");
        json("/hms/v3/content/fetch-city-regionIds?limit=2000&cursor=P2", HttpMethod.GET, """
                {"hotelCityRegionIds":[{"cityName":"Paris","cityRegionId":31,"countryName":"United States"}],"hasMore":false}""");
    }

    /** What the startup job loads: the city list and nationalities, before any search. */
    private void staticData() {
        cityList();
        json("/hms/v3/nationality-info", HttpMethod.GET, """
                {"nationalityInfos":[{"countryId":"106","code":"IN","name":"India"}]}""");
    }

    @Test
    void cityBecomesHotelIdsAndTheCheapestOptionOfEachHotelIsOffered() {
        staticData();
        server.expect(requestTo(HOST + "/hms/v3/content/fetch-hotel-mapping")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.regionIds[0]").value("21"))
                .andRespond(withSuccess("""
                        {"hotels":[{"tjHotelId":"1001"},{"tjHotelId":"1002"}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOST + "/hms/v3/hotel/listing")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.checkIn").value("2026-12-01"))
                .andExpect(jsonPath("$.nationality").value("106"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.hids[0]").value(1001))
                .andExpect(jsonPath("$.rooms[0].adults").value(2))
                .andExpect(jsonPath("$.rooms[0].childAge[0]").value(8))
                .andRespond(withSuccess("""
                        {"hotels":[
                          {"tjHotelId":"1001","name":"Hotel A","options":[
                            {"optionId":"A-2","roomInfo":[{"name":"Suite"}],"mealBasis":"Breakfast","pricing":{"totalPrice":30000,"currency":"INR"},"cancellation":{"isRefundable":false}},
                            {"optionId":"A-1","roomInfo":[{"name":"Deluxe Room"}],"mealBasis":"Room Only","pricing":{"totalPrice":21000.5,"currency":"INR"},
                             "cancellation":{"isRefundable":true,"deadlineDateTime":"2026-11-28T12:00"}}]},
                          {"tjHotelId":"1002","name":"Hotel B","options":[]}]}""", MediaType.APPLICATION_JSON));
        // No city list, nationality or content call during the search (server.verify() fails on any unexpected call).

        provider.refreshStaticData();
        List<HotelOfferResponse> offers = provider.search(query("Paris", "FR"));

        server.verify();
        assertThat(offers).hasSize(1);
        HotelOfferResponse o = offers.get(0);
        assertThat(o.getOfferId()).isEqualTo("A-1");
        assertThat(o.getHotelName()).isEqualTo("Hotel A");
        assertThat(o.getHotelId()).isEqualTo("1001");
        // The listing carries no photo, stars or address here, so the card goes without them.
        assertThat(o.getImageUrl()).isNull();
        assertThat(o.getStarRating()).isZero();
        assertThat(o.getRoomType()).isEqualTo("Deluxe Room");
        assertThat(o.getBoardBasis()).isEqualTo("Room Only");
        assertThat(o.getNights()).isEqualTo(3);
        assertThat(o.getPrice()).isEqualByComparingTo("21000.50");
        assertThat(o.isRefundable()).isTrue();
        assertThat(o.getCancellationPolicy()).isEqualTo("Free cancellation until 2026-11-28 12:00");
        assertThat(o.getSupplier()).isEqualTo("Tripjack");
    }

    @Test
    void anUnknownCityIsAClearError() {
        staticData();
        provider.refreshStaticData();
        assertThatThrownBy(() -> provider.search(query("Atlantis", null)))
                .isInstanceOf(SupplierException.class)
                .hasMessageContaining("no hotels listed for \"Atlantis\"");
    }

    @Test
    void tripjacksErrorMessageIsPassedOn() {
        server.expect(requestTo(HOST + "/hms/v3/content/fetch-city-regionIds?limit=2000"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"message\":\"IP not whitelisted\"}]}"));
        assertThatThrownBy(provider::refreshStaticData)
                .isInstanceOf(SupplierException.class)
                .hasMessageContaining("HTTP 403").hasMessageContaining("IP not whitelisted");
    }

    @Test
    void aSearchNeverLoadsTheCityListItselfAndAsksToRetryUntilItIsLoaded() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(HOST)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> provider.search(query("Goa", "IN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still loading");
    }

    @Test
    void aFreshCopyIsNotLoadedAgain() {
        staticData();
        provider.refreshStaticData();
        provider.refreshStaticData(); // A second load would hit an unexpected request and fail.
        server.verify();
    }

    @Test
    void thumbnailsComeFromTheContentCallAtListCardSizeAndAreCached() {
        server.expect(requestTo(HOST + "/hms/v3/content/fetch-hotel-content")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.hotelIds[0]").value("1001"))
                .andRespond(withSuccess("""
                        {"hotels":[{"tjHotelId":"1001","star_rating":"4.0",
                          "locale":{"address":{"fulladdr":"Beach Road, Goa"}},
                          "images":[{"links":{"1000px":{"href":"https://i.travelapi.com/lodging/1/abc_z.jpg"}}}]}]}""", MediaType.APPLICATION_JSON));

        var first = provider.thumbnails(List.of("1001", "1002"));
        var again = provider.thumbnails(List.of("1001"));

        server.verify();
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getHotelId()).isEqualTo("1001");
        assertThat(first.get(0).getImageUrl()).isEqualTo("https://i.travelapi.com/lodging/1/abc_b.jpg");
        assertThat(first.get(0).getStarRating()).isEqualTo(4);
        assertThat(first.get(0).getAddress()).isEqualTo("Beach Road, Goa");
        assertThat(again).hasSize(1);
    }

    @Test
    void guestsAreSpreadOverTheRooms() {
        HotelSearchQuery q = query("Goa", "IN");
        q.setRooms(2);
        q.setAdults(3);
        q.setChildren(1);
        q.setChildAges(List.of(5));
        List<Map<String, Object>> rooms = TripjackHotelSearchProvider.rooms(q);
        assertThat(rooms.get(0)).containsEntry("adults", 2).containsEntry("children", 1).containsEntry("childAge", List.of(5));
        assertThat(rooms.get(1)).containsEntry("adults", 1).doesNotContainKey("children");
    }
}
