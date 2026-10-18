package com.voyra.crm.service.tripjack;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.service.SupplierCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Runs against a saved fixture in src/test/resources/tripjack/, never the live API - so this
 * stays offline and deterministic. The fixture mirrors the contract documented in
 * TripjackFlightSearchProvider's class javadoc; if a real UAT response later disagrees with
 * it, this test is the first thing to update.
 */
@ExtendWith(MockitoExtension.class)
class TripjackFlightSearchProviderTest {

    @Mock
    private SupplierCredentialService supplierCredentialService;

    private SupplierCredentialRevealResponse credential() {
        return SupplierCredentialRevealResponse.builder()
                .provider(SupplierProvider.TRIPJACK)
                .environment(SupplierEnvironment.UAT)
                .baseUrl("https://apitest.tripjack.com")
                .apiKey("test-key")
                .build();
    }

    private String fixture(String classpathPath) throws java.io.IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    private FlightSearchQuery query() {
        FlightSearchQuery query = new FlightSearchQuery();
        query.setOrigin("Bangalore");
        query.setDestination("Phuket");
        query.setDepartDate(LocalDate.of(2026, 10, 9));
        query.setCabin(FlightCabin.ECONOMY);
        query.setAdults(2);
        return query;
    }

    @Test
    void search_sendsTheDocumentedRequestShapeAndMapsBothFareOptions() throws Exception {
        when(supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(credential());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://apitest.tripjack.com/fms/v1/air-search-all"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("apikey", "test-key"))
                .andExpect(jsonPath("$.searchQuery.cabinClass").value("ECONOMY"))
                .andExpect(jsonPath("$.searchQuery.paxInfo.ADULT").value(2))
                .andExpect(jsonPath("$.searchQuery.routeInfos[0].fromCityOrAirport.code").value("BLR"))
                .andExpect(jsonPath("$.searchQuery.routeInfos[0].toCityOrAirport.code").value("HKT"))
                .andExpect(jsonPath("$.searchQuery.routeInfos[0].travelDate").value("2026-10-09"))
                .andRespond(withSuccess(fixture("tripjack/flight-search-response.json"), MediaType.APPLICATION_JSON));

        TripjackFlightSearchProvider provider = new TripjackFlightSearchProvider(supplierCredentialService, builder);
        List<FlightOfferResponse> offers = provider.search(query());

        server.verify();
        assertThat(offers).hasSize(2);

        FlightOfferResponse saver = offers.get(0);
        assertThat(saver.getOfferId()).isEqualTo("off-saver-1");
        assertThat(saver.getAirline()).isEqualTo("IndiGo");
        assertThat(saver.getAirlineCode()).isEqualTo("6E");
        assertThat(saver.getFlightNumber()).isEqualTo("6E-204");
        assertThat(saver.getOrigin()).isEqualTo("BLR");
        assertThat(saver.getDestination()).isEqualTo("HKT");
        assertThat(saver.getDepartTime().toString()).isEqualTo("06:15");
        assertThat(saver.getArriveTime().toString()).isEqualTo("09:20");
        assertThat(saver.getStops()).isZero();
        assertThat(saver.getPrice()).isEqualByComparingTo("8400.00");
        assertThat(saver.isRefundable()).isFalse();
        assertThat(saver.getFareType()).isEqualTo("Saver");

        FlightOfferResponse flexi = offers.get(1);
        assertThat(flexi.getOfferId()).isEqualTo("off-flexi-1");
        assertThat(flexi.isRefundable()).isTrue();
        assertThat(flexi.getFareType()).isEqualTo("Flexi");
    }

    @Test
    void search_totalCoversEveryPassengerAtTheirOwnFare() throws Exception {
        when(supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(credential());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://apitest.tripjack.com/fms/v1/air-search-all"))
                .andRespond(withSuccess(fixture("tripjack/flight-search-response.json"), MediaType.APPLICATION_JSON));
        FlightSearchQuery family = query();
        family.setChildren(1);
        family.setInfants(1);

        List<FlightOfferResponse> offers = new TripjackFlightSearchProvider(supplierCredentialService, builder).search(family);

        // Saver quotes all three passenger types: 2 x 8,400 + 6,300 + 1,000.
        assertThat(offers.get(0).getPrice()).isEqualByComparingTo("8400.00");
        assertThat(offers.get(0).getTotalPrice()).isEqualByComparingTo("24100.00");
        assertThat(offers.get(0).getPassengers()).isEqualTo(4);
        // Flexi only quotes the adult fare, so every passenger is priced at it rather than under-quoted.
        assertThat(offers.get(1).getTotalPrice()).isEqualByComparingTo("40800.00");
    }

    @Test
    void search_connectingFlightCountsEveryStopAndTheWholeJourneyTime() throws Exception {
        when(supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(credential());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://apitest.tripjack.com/fms/v1/air-search-all"))
                .andRespond(withSuccess(fixture("tripjack/flight-search-connecting-response.json"), MediaType.APPLICATION_JSON));

        FlightOfferResponse offer = new TripjackFlightSearchProvider(supplierCredentialService, builder).search(query()).get(0);

        // One change of plane at DEL plus one technical stop on the second leg.
        assertThat(offer.getStops()).isEqualTo(2);
        // 120 min flying + 150 min layover at DEL + 300 min flying - not just the first leg's 120.
        assertThat(offer.getDuration()).isEqualTo("570");
        assertThat(offer.getOrigin()).isEqualTo("BLR");
        assertThat(offer.getDestination()).isEqualTo("HKT");
        assertThat(offer.getDepartDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 9));
        assertThat(offer.getArriveDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 9));
    }

    @Test
    void parseFlightDate_takesTheDateFromAnIsoDatetimeAndFallsBackForABareTime() {
        assertThat(TripjackFlightSearchProvider.parseFlightDate("2026-10-10T01:40:00", null))
                .isEqualTo(java.time.LocalDate.of(2026, 10, 10));
        assertThat(TripjackFlightSearchProvider.parseFlightDate("01:40", java.time.LocalDate.of(2026, 10, 9)))
                .isEqualTo(java.time.LocalDate.of(2026, 10, 9));
    }

    @Test
    void search_unknownCityThrowsBeforeAnyHttpCall() {
        FlightSearchQuery query = query();
        query.setOrigin("Atlantis");

        assertThatThrownBy(() -> new TripjackFlightSearchProvider(supplierCredentialService, RestClient.builder())
                .search(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown city");
    }

    @Test
    void search_supplierErrorStatusBecomesASupplierException() throws Exception {
        when(supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(credential());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://apitest.tripjack.com/fms/v1/air-search-all"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        TripjackFlightSearchProvider provider = new TripjackFlightSearchProvider(supplierCredentialService, builder);

        assertThatThrownBy(() -> provider.search(query()))
                .isInstanceOf(SupplierException.class);
    }

    /**
     * Fixture captured verbatim from a real UAT call (invalid-key case) - confirms Tripjack's
     * functional-error envelope is {@code errors[0].message}, not {@code status.message} as
     * first assumed. Tripjack answers this with HTTP 200 and success:false in the body.
     */
    @Test
    void search_functionalErrorEnvelopeSurfacesTripjacksRealMessage() throws Exception {
        when(supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK)).thenReturn(credential());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://apitest.tripjack.com/fms/v1/air-search-all"))
                .andRespond(withSuccess(fixture("tripjack/flight-search-error-response.json"), MediaType.APPLICATION_JSON));

        TripjackFlightSearchProvider provider = new TripjackFlightSearchProvider(supplierCredentialService, builder);

        assertThatThrownBy(() -> provider.search(query()))
                .isInstanceOf(SupplierException.class)
                .hasMessageContaining("The provided API key is invalid");
    }
}
