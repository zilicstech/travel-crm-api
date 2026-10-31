package com.voyra.crm.service.tripjack;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.ResponseCreator;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request defaults, odd bodies and the segment shapes the fixture tests leave out, with inline made-up responses. */
@ExtendWith(MockitoExtension.class)
class TripjackFlightSearchProviderCoverageTest {

    private static final String URL = "https://apitest.tripjack.com/fms/v1/air-search-all";

    @Mock
    private PlatformSupplierCredentialService credentials;

    private static ResolvedCredential credential(String key) {
        return new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT, "https://apitest.tripjack.com", key, null);
    }

    private static FlightSearchQuery query() {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin("BLR");
        q.setDestination("HKT");
        q.setDepartDate(LocalDate.of(2026, 10, 9));
        q.setAdults(1);
        return q;
    }

    private static String ok(String onward) {
        return "{\"status\":{\"success\":true},\"searchResult\":{\"tripInfos\":{\"ONWARD\":[" + onward + "]}}}";
    }

    private static String seg(String dt, String at, String duration) {
        return "{\"fD\":{\"aI\":{\"code\":\"XX\",\"name\":\"Test Air\"},\"fN\":\"XX-1\"},\"da\":{\"code\":\"BLR\"},\"aa\":{\"code\":\"HKT\"},"
                + "\"dt\":\"" + dt + "\",\"at\":\"" + at + "\",\"duration\":\"" + duration + "\"}";
    }

    private static final String FARE = "{\"id\":\"F1\",\"sri\":\" \",\"fd\":{\"ADULT\":{\"fC\":{\"TF\":5000,\"BF\":\" \"}},"
            + "\"CHILD\":{\"fC\":{\"TF\":\" \"}}}}";

    private List<FlightOfferResponse> search(FlightSearchQuery q, ResolvedCredential c, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(jsonPath("$.searchQuery.cabinClass").value("ECONOMY"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        try {
            return new TripjackFlightSearchProvider(credentials, builder).search(q, c);
        } finally {
            server.verify();
        }
    }

    @Test
    void noActivePlatformCredentialStopsTheSearch() {
        when(credentials.resolveActive(SupplierProvider.TRIPJACK)).thenThrow(new IllegalStateException("No active credential"));

        assertThatThrownBy(() -> new TripjackFlightSearchProvider(credentials, RestClient.builder()).search(query()))
                .isInstanceOf(IllegalStateException.class).hasMessage("No active credential");
    }

    @Test
    void aMissingCabinSearchesEconomyAndBlankOrMissingFaresLeaveNoBase() {
        List<FlightOfferResponse> offers = search(query(), credential(null), ok(
                "{\"sI\":[" + seg("2026-10-09T22:00", "2026-10-10T04:00", "6h") + "],\"totalPriceList\":[" + FARE + "]}"));

        assertThat(offers).singleElement().satisfies(o -> {
            assertThat(o.getPrice()).isEqualByComparingTo("5000");
            assertThat(o.getBaseFare()).isNull();
            assertThat(o.getSpecialReturnId()).isNull();
            assertThat(o.getFareType()).isEqualTo("Standard");
            assertThat(o.isRefundable()).isFalse();
            assertThat(o.getDuration()).isEqualTo("360");
            assertThat(o.getArriveDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        });
    }

    @Test
    void tripsWithoutSegmentsAreSkipped() {
        List<FlightOfferResponse> offers = search(query(), credential("abcd"),
                ok("{\"sI\":[],\"totalPriceList\":[" + FARE + "]},{\"sI\":{},\"totalPriceList\":[" + FARE + "]}"));

        assertThat(offers).isEmpty();
    }

    @Test
    void bareTimesUseTheSearchDateAndAnOvernightArrivalIsTheNextDay() {
        List<FlightOfferResponse> offers = search(query(), credential("long-test-key"),
                ok("{\"sI\":[" + seg("22:15", "Arrives 01:30 local", "") + "],\"totalPriceList\":[" + FARE + "]}"));

        assertThat(offers).singleElement().satisfies(o -> {
            assertThat(o.getDepartTime()).isEqualTo(LocalTime.of(22, 15));
            assertThat(o.getArriveTime()).isEqualTo(LocalTime.of(1, 30));
            assertThat(o.getDepartDate()).isEqualTo(LocalDate.of(2026, 10, 9));
            assertThat(o.getArriveDate()).isEqualTo(LocalDate.of(2026, 10, 10));
            assertThat(o.getDuration()).isEmpty();
        });
    }

    @Test
    void aBareTimeWithoutASearchDateHasNoDates() {
        FlightSearchQuery q = query();
        q.setDepartDate(LocalDate.of(2026, 10, 9));
        q.setReturnDate(null);
        String body = "{\"status\":{\"success\":true},\"searchResult\":{\"tripInfos\":{\"RETURN\":[{\"sI\":["
                + seg("08:00", "10:00", "2h") + "],\"totalPriceList\":[" + FARE + "]}]}}}";

        List<FlightOfferResponse> offers = search(q, credential("long-test-key"), body);

        assertThat(offers).singleElement().satisfies(o -> {
            assertThat(o.getDepartDate()).isNull();
            assertThat(o.getArriveDate()).isNull();
        });
    }

    @Test
    void anUnreadableDurationOrLayoverFallsBackToTheFirstSegmentsDuration() {
        String twoSegs = seg("2026-10-09T06:00", "2026-10-09T08:00", "2h") + ","
                + seg("not-a-datetime-10:00", "2026-10-09T12:00", "2h");
        String badSecondDuration = seg("2026-10-09T06:00", "2026-10-09T08:00", "2h") + ","
                + seg("2026-10-09T09:00", "2026-10-09T12:00", "?") + "," + seg("2026-10-09T13:00", "2026-10-09T14:00", "1h");

        List<FlightOfferResponse> layover = search(query(), credential("long-test-key"),
                ok("{\"sI\":[" + twoSegs + "],\"totalPriceList\":[" + FARE + "]}"));
        List<FlightOfferResponse> duration = search(query(), credential("long-test-key"),
                ok("{\"sI\":[" + badSecondDuration + "],\"totalPriceList\":[" + FARE + "]}"));

        assertThat(layover.get(0).getDuration()).isEqualTo("2h");
        assertThat(layover.get(0).getStops()).isEqualTo(1);
        assertThat(duration.get(0).getDuration()).isEqualTo("2h");
        assertThat(duration.get(0).getStops()).isEqualTo(2);
    }

    @Test
    void aMissingOrUnparseableTimeIsASupplierError() {
        assertThatThrownBy(() -> search(query(), credential("long-test-key"),
                ok("{\"sI\":[" + seg("", "10:00", "2h") + "],\"totalPriceList\":[]}")))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack flight response is missing a departure/arrival time");
        assertThatThrownBy(() -> search(query(), credential("long-test-key"),
                ok("{\"sI\":[" + seg("noon", "10:00", "2h") + "],\"totalPriceList\":[]}")))
                .isInstanceOf(SupplierException.class).hasMessage("Could not parse Tripjack flight time: \"noon\"");
    }

    @Test
    void anEmptyBodyIsAnEmptyResponseError() {
        assertThatThrownBy(() -> search(query(), credential("long-test-key"), ""))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack flight search returned an error: Empty response");
    }

    @Test
    void aNonJsonBodyIsUnparseable() {
        assertThatThrownBy(() -> search(query(), credential("long-test-key"), "<html>"))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack flight search returned an unparseable response");
    }

    @Test
    void anUnknownAirportFailsBeforeAnyCall() {
        FlightSearchQuery q = query();
        q.setOrigin("Atlantis Unknown");

        assertThatThrownBy(() -> new TripjackFlightSearchProvider(credentials, RestClient.builder()).search(q, credential("k")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(credentials);
    }

    /** A provider whose IP lookup answers from {@code ipAnswer} instead of the network; the server is returned for verify. */
    private static MockRestServiceServer ipLookup(TripjackFlightSearchProvider provider, ResponseCreator ipAnswer) {
        RestClient.Builder ipBuilder = RestClient.builder();
        MockRestServiceServer ipServer = MockRestServiceServer.bindTo(ipBuilder).build();
        ipServer.expect(requestTo("https://api.ipify.org")).andRespond(ipAnswer);
        provider.ipLookup = ipBuilder.build();
        return ipServer;
    }

    @Test
    void anUnreachableSupplierIsASupplierErrorAndAsksForThisServersIp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));
        TripjackFlightSearchProvider provider = new TripjackFlightSearchProvider(credentials, builder);
        MockRestServiceServer ipServer = ipLookup(provider, withSuccess("203.0.113.7", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.search(query(), credential("long-test-key")))
                .isInstanceOf(SupplierException.class).hasMessage("Tripjack flight search call failed")
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        server.verify();
        ipServer.verify();
    }

    @Test
    void theOutboundIpIsTrimmedUnknownWhenEmptyAndNamesTheFailureWhenTheLookupFails() {
        TripjackFlightSearchProvider provider = new TripjackFlightSearchProvider(credentials, RestClient.builder());

        ipLookup(provider, withSuccess(" 203.0.113.7\n", MediaType.TEXT_PLAIN));
        assertThat(provider.outboundIp()).isEqualTo("203.0.113.7");

        ipLookup(provider, withSuccess());
        assertThat(provider.outboundIp()).isEqualTo("unknown");

        ipLookup(provider, withServerError());
        assertThat(provider.outboundIp()).isEqualTo("unknown (lookup failed: InternalServerError)");
        verifyNoInteractions(credentials);
    }
}
