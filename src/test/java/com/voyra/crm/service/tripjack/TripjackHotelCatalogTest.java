package com.voyra.crm.service.tripjack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.CatalogHotel;
import com.voyra.crm.models.CatalogSummary;
import com.voyra.crm.models.ResolvedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The hotel catalogue load for name search, and searching one picked hotel, answered offline by MockRestServiceServer. */
class TripjackHotelCatalogTest {

    private static final String UAT = "https://apitest-hms.tripjack.com";
    private static final String MAPPING = UAT + "/hms/v3/content/fetch-hotel-mapping";
    private static final String CONTENT = UAT + "/hms/v3/content/fetch-hotel-content";
    private static final ResolvedCredential CRED = new ResolvedCredential("s1", "Tripjack", SupplierProvider.TRIPJACK,
            SupplierEnvironment.UAT, "https://unused.example.test", "test-key", null, null);

    private MockRestServiceServer server;
    private TripjackHotelSearchProvider provider;
    private final List<List<CatalogHotel>> batches = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        provider = new TripjackHotelSearchProvider(builder, UAT, "https://hms-search.tripjack.com");
        provider.catalogRetryDelay = Duration.ZERO;
        provider.catalogCallsPerSecond = 1000;
        provider.catalogContentBatch = 2;
    }

    private void json(String url, String body) {
        server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void staticData() {
        json(UAT + "/hms/v3/content/fetch-city-regionIds?limit=2000",
                "{\"hotelCityRegionIds\":[{\"cityName\":\"North Goa\",\"cityRegionId\":11,\"countryName\":\"India\"}]}");
        json(UAT + "/hms/v3/nationality-info", "{\"nationalityInfos\":[{\"countryId\":\"106\",\"code\":\"IN\"}]}");
    }

    private void mapping(int page, String body) {
        server.expect(requestTo(MAPPING)).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.countryName").value("INDIA"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String ids(String... ids) {
        return String.join(",", java.util.Arrays.stream(ids).map(i -> "{\"tjHotelId\":\"" + i + "\"}").toList());
    }

    private CatalogSummary load() {
        return provider.catalog(CRED, batches::add);
    }

    @Test
    void everyActiveHotelIsNamedWithTripjacksCityAndHandedOverAPageAtATime() {
        staticData();
        mapping(0, "{\"hotels\":[" + ids("1", "2", "x", "2") + "],\"pageable\":{\"totalPages\":2,\"totalElements\":3}}");
        mapping(1, "{\"hotels\":[" + ids("3") + "]}");
        json(CONTENT, """
                {"hotels":[
                 {"tjHotelId":"1","name":"Taj Exotica","is_active":true,"star_rating":4.6,"property_type":{"name":"Resort"},
                  "locale":{"address":{"regioncode":"11","city":"Morjim","countrycode":"IN","countryname":"India"}}},
                 {"tjHotelId":"2","name":"Closed Inn","is_active":false,"locale":{"address":{"city":"Goa"}}}]}""");
        json(CONTENT, """
                {"hotels":[
                 {"tjHotelId":"3","name":"Panaji Stay","is_active":null,"star_rating":0,"locale":{"address":{"city":{"name":"Panaji"}}}},
                 {"tjHotelId":"4","locale":{"address":{"city":"Goa"}}},
                 {"tjHotelId":"5","name":"Nowhere"},
                 {"tjHotelId":"abc","name":"Not Tripjack's","locale":{"address":{"city":"Goa"}}}]}""");

        CatalogSummary summary = load();

        server.verify();
        assertThat(summary).isEqualTo(new CatalogSummary(3, 2, 0, 2, null));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsExactly(new CatalogHotel("1", "Taj Exotica", "North Goa", "IN", "India", 5, "Resort"));
        assertThat(batches.get(1)).containsExactly(new CatalogHotel("3", "Panaji Stay", "Panaji", null, null, null, null));
    }

    @Test
    void contentCallsAreRetriedOnceThenCountedAndAnEmptyPageIsSkipped() {
        staticData();
        provider.catalogCallsPerSecond = 20; // one call every 50 ms, so the second waits its turn
        mapping(0, "{\"hotels\":[" + ids("1", "2", "3", "4") + "],\"totalPages\":3,\"totalElements\":4}");
        mapping(1, "{\"hotels\":[]}");
        mapping(2, "{\"hotels\":[]}");
        server.expect(requestTo(CONTENT)).andExpect(jsonPath("$.hotelIds[0]").value("1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(CONTENT)).andExpect(jsonPath("$.hotelIds[0]").value("1"))
                .andRespond(withSuccess("{\"hotels\":[{\"tjHotelId\":\"1\",\"name\":\"A\",\"locale\":{\"address\":{\"city\":\"Goa\"}}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CONTENT)).andExpect(jsonPath("$.hotelIds[0]").value("3")).andRespond(withException(new IOException("reset")));
        server.expect(requestTo(CONTENT)).andExpect(jsonPath("$.hotelIds[0]").value("3")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        CatalogSummary summary = load();

        assertThat(summary).isEqualTo(new CatalogSummary(4, 1, 1, 2, null));
    }

    @Test
    void tripjackThrottlingUsOutStopsTheLoad() {
        staticData();
        provider.catalogMaxThrottled = 1;
        mapping(0, "{\"hotels\":[" + ids("1") + "],\"pageable\":{\"totalPages\":1,\"totalElements\":1}}");
        server.expect(requestTo(CONTENT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(CONTENT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(load().fatal()).isEqualTo("Tripjack throttled the catalogue load 1 times in a row");
    }

    @Test
    void aLostMappingPageARepeatedPageEndlessPagingAndAShortListingAreFatal() {
        staticData();
        server.expect(requestTo(MAPPING)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(MAPPING)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(load().fatal()).startsWith("Hotel id page 0 of INDIA failed: Tripjack hotel search failed");

        server.reset();
        String twoPages = "{\"hotels\":[" + ids("7") + "],\"pageable\":{\"totalPages\":2,\"totalElements\":2}}";
        mapping(0, twoPages);
        mapping(1, twoPages);
        json(CONTENT, "{\"hotels\":[]}");
        assertThat(load().fatal()).isEqualTo("Tripjack returned the same hotel id page twice for INDIA");

        server.reset();
        provider.catalogMaxPages = 1;
        mapping(0, "{\"hotels\":[],\"pageable\":{\"totalPages\":2,\"totalElements\":0}}");
        assertThat(load().fatal()).isEqualTo("More than 1 mapping pages for INDIA");

        server.reset();
        provider.catalogMaxPages = 1000;
        mapping(0, "{\"hotels\":[" + ids("8") + "],\"pageable\":{\"totalPages\":1,\"totalElements\":20}}");
        json(CONTENT, "{\"hotels\":[]}");
        assertThat(load().fatal()).isEqualTo("Tripjack listed 1 of 20 hotel ids for INDIA");
    }

    @Test
    void anInterruptedRetryIsALostPage() {
        staticData();
        provider.catalogRetryDelay = Duration.ofMillis(5);
        server.expect(requestTo(MAPPING)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        provider.refreshStaticData(CRED);
        Thread.currentThread().interrupt();
        try {
            assertThat(load().fatal()).isEqualTo("Hotel id page 0 of INDIA failed: Interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void citiesReadAsPeopleWriteThem() {
        assertThat(TripjackHotelSearchProvider.cityName("NEW DELHI")).isEqualTo("New Delhi");
        assertThat(TripjackHotelSearchProvider.cityName("PORT-BLAIR")).isEqualTo("Port-Blair");
        assertThat(TripjackHotelSearchProvider.catalogHotel(new ObjectMapper().createObjectNode().put("tjHotelId", "1").put("name", "A"),
                Map.of())).isNull();
    }

    @Test
    void aHotelPickedByNameIsListedAloneAndANonTripjackIdListsNothing() {
        staticData();
        server.expect(requestTo(UAT + "/hms/v3/hotel/listing")).andExpect(jsonPath("$.hids.length()").value(1)).andExpect(jsonPath("$.hids[0]").value(42))
                .andRespond(withSuccess("{\"hotels\":[{\"tjHotelId\":\"42\",\"name\":\"Taj\",\"options\":[{\"optionId\":\"O\",\"pricing\":{\"totalPrice\":900}}]}]}",
                        MediaType.APPLICATION_JSON));
        json(CONTENT, "{\"hotels\":[]}");
        HotelSearchQuery q = new HotelSearchQuery();
        q.setCity("Morjim");
        q.setHotelId("42");
        q.setCheckIn(LocalDate.of(2026, 12, 1));
        q.setCheckOut(LocalDate.of(2026, 12, 2));
        provider.refreshStaticData(CRED);

        assertThat(provider.search(q, CRED)).extracting(HotelOfferResponse::getCity).containsExactly("Morjim");

        q.setHotelId("mock-amari");
        assertThat(provider.search(q, CRED)).isEmpty();
        server.verify();
    }
}
