package com.voyra.crm.service;

import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchPageRequest;
import com.voyra.crm.dto.FlightSearchPageResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FlightSortOrder;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FlightSearchServiceTest {

    private final AtomicInteger supplierCalls = new AtomicInteger();

    /** 25 flights x 2 fares; flight i costs 1000+i (so price order == index order) and departs later as i grows. */
    private final FlightSearchProvider provider = query -> {
        supplierCalls.incrementAndGet();
        List<FlightOfferResponse> offers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            for (int fare = 0; fare < 2; fare++) {
                offers.add(FlightOfferResponse.builder()
                        .offerId("o" + i + "-" + fare).airline(i % 2 == 0 ? "IndiGo" : "Air India").airlineCode(i % 2 == 0 ? "6E" : "AI")
                        .flightNumber("F" + i).origin("BLR").destination("GOI")
                        .departTime(LocalTime.of(5, 0).plusMinutes(i * 10L)).arriveTime(LocalTime.of(7, 0).plusMinutes(i * 10L))
                        .duration(String.valueOf(200 - i)).stops(i % 5 == 0 ? 1 : 0)
                        .price(BigDecimal.valueOf(1000 + i + fare * 500)).refundable(fare == 1).fareType("X").build());
            }
        }
        // Deliberately shuffled input order: grouping/sorting must not rely on supplier order.
        java.util.Collections.reverse(offers);
        return offers;
    };

    private final com.voyra.crm.service.booking.AgencyPriceQuoter quoter = org.mockito.Mockito.mock(com.voyra.crm.service.booking.AgencyPriceQuoter.class);

    private final FlightSearchService service = new FlightSearchService(provider, quoter);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsTenant(String tenantId) {
        CustomUserPrincipal principal = new CustomUserPrincipal("u-" + tenantId, "owner", UserType.AGENCY_OWNER, tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    private FlightSearchPageRequest request(int page) {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin("BLR");
        q.setDestination("GOI");
        q.setDepartDate(LocalDate.of(2026, 11, 25));
        FlightSearchPageRequest r = new FlightSearchPageRequest();
        r.setQuery(q);
        r.setPage(page);
        r.setPageSize(10);
        return r;
    }

    @Test
    void defaultsToCheapestFirstAndPagesByFlightNotByFare() {
        loginAsTenant("t1");
        FlightSearchPageResponse p1 = service.searchPage(request(1));

        assertThat(p1.getTotalFlights()).isEqualTo(25);
        assertThat(p1.getTotalPages()).isEqualTo(3);
        assertThat(p1.getFlights()).hasSize(10);
        assertThat(p1.getFlights().get(0).getFlightNumber()).isEqualTo("F0");
        assertThat(p1.getFlights().get(0).getFares()).hasSize(2);
        assertThat(p1.getFlights().get(0).getFares().get(0).getPrice()).isEqualByComparingTo("1000");
        assertThat(p1.getCheapestKey()).isEqualTo(p1.getFlights().get(0).getKey());
        assertThat(service.searchPage(request(3)).getFlights()).hasSize(5);
    }

    @Test
    void pagingSortingAndFilteringReuseTheCachedSupplierResult() {
        loginAsTenant("t1");
        service.searchPage(request(1));
        FlightSearchPageRequest next = request(2);
        service.searchPage(next);
        FlightSearchPageRequest sorted = request(1);
        sorted.setSort(FlightSortOrder.DURATION);
        service.searchPage(sorted);

        assertThat(supplierCalls.get()).isEqualTo(1);
    }

    @Test
    void refreshForcesANewSupplierCall() {
        loginAsTenant("t1");
        service.searchPage(request(1));
        FlightSearchPageRequest again = request(1);
        again.setRefresh(true);
        service.searchPage(again);

        assertThat(supplierCalls.get()).isEqualTo(2);
    }

    @Test
    void oneAgencysCachedResultIsNeverServedToAnother() {
        loginAsTenant("t1");
        service.searchPage(request(1));
        loginAsTenant("t2");
        service.searchPage(request(1));

        assertThat(supplierCalls.get()).isEqualTo(2);
    }

    @Test
    void filtersNarrowTheResultButNotTheAirlineFacetOrTheCache() {
        loginAsTenant("t1");
        FlightSearchPageRequest r = request(1);
        r.setStops(0);
        r.setAirlines(List.of("IndiGo"));
        r.setRefundableOnly(true);
        FlightSearchPageResponse res = service.searchPage(r);

        // IndiGo = even i; stops==0 excludes i%5==0 (0,10,20) -> 13 even minus 3 = 10 flights.
        assertThat(res.getTotalFlights()).isEqualTo(10);
        assertThat(res.getFlights()).allSatisfy(g -> {
            assertThat(g.getAirline()).isEqualTo("IndiGo");
            assertThat(g.getStops()).isZero();
            assertThat(g.getFares()).hasSize(1);
            assertThat(g.getFares().get(0).isRefundable()).isTrue();
        });
        assertThat(res.getAirlines()).containsExactly("Air India", "IndiGo");

        // The filter must not have trimmed the cached flights: an unfiltered follow-up still sees 25.
        assertThat(service.searchPage(request(1)).getTotalFlights()).isEqualTo(25);
        assertThat(supplierCalls.get()).isEqualTo(1);
    }

    @Test
    void oneStopMeansExactlyOneStopNotUpToOne() {
        loginAsTenant("t1");
        FlightSearchPageRequest r = request(1);
        r.setStops(1);
        FlightSearchPageResponse res = service.searchPage(r);

        // i%5==0 -> flights 0,5,10,15,20 have one stop; the 20 non-stop flights must not appear.
        assertThat(res.getTotalFlights()).isEqualTo(5);
        assertThat(res.getFlights()).allSatisfy(g -> assertThat(g.getStops()).isEqualTo(1));

        r.setStops(2);
        assertThat(service.searchPage(r).getTotalFlights()).isZero();
    }

    @Test
    void airlineCodesSurviveGroupingAndTheRefundableCopyAndFeedTheFacet() {
        loginAsTenant("t1");
        FlightSearchPageRequest req = request(1);
        req.setRefundableOnly(true);
        FlightSearchPageResponse res = service.searchPage(req);

        assertThat(res.getFlights()).isNotEmpty()
                .allSatisfy(g -> assertThat(g.getAirlineCode()).isIn("6E", "AI"));
        assertThat(res.getAirlineCodes()).containsEntry("IndiGo", "6E").containsEntry("Air India", "AI");
    }

    @Test
    void durationSortUsesMinutesAndDepartureSortUsesClockTime() {
        loginAsTenant("t1");
        FlightSearchPageRequest byDuration = request(1);
        byDuration.setSort(FlightSortOrder.DURATION);
        // duration = 200 - i, so the longest-index flight is fastest.
        assertThat(service.searchPage(byDuration).getFlights().get(0).getFlightNumber()).isEqualTo("F24");
        assertThat(service.searchPage(byDuration).getFastestKey()).isNotNull();

        FlightSearchPageRequest byDeparture = request(1);
        byDeparture.setSort(FlightSortOrder.DEPARTURE);
        assertThat(service.searchPage(byDeparture).getFlights().get(0).getFlightNumber()).isEqualTo("F0");
    }

    @Test
    void pageNumberBeyondTheEndIsClampedAndTextDurationsParse() {
        loginAsTenant("t1");
        assertThat(service.searchPage(request(99)).getPage()).isEqualTo(3);
        assertThat(FlightResultPaging.durationMinutes("3h 05m")).isEqualTo(185);
        assertThat(FlightResultPaging.durationMinutes("140")).isEqualTo(140);
        assertThat(FlightResultPaging.durationMinutes("")).isEqualTo(Integer.MAX_VALUE);
    }

    private FlightOfferResponse firstFare(FlightSearchPageResponse p) {
        return p.getFlights().get(0).getFares().get(0);
    }

    @Test
    void noPricingRowMeansListedButNotBookable() {
        org.mockito.Mockito.when(quoter.quoterFor("t1", com.voyra.crm.enums.PricingProduct.FLIGHT)).thenReturn(java.util.Optional.empty());
        loginAsTenant("t1");
        FlightOfferResponse o = firstFare(service.searchPage(request(1)));
        assertThat(o.isBookable()).isFalse();
        assertThat(o.getNotBookableReason()).isEqualTo("PRICING_NOT_CONFIGURED");
        assertThat(o.getDisplayedFare()).isEqualByComparingTo(o.getPublishedFare());
        assertThat(o.getAgencyEarns()).isEqualByComparingTo("0");
    }

    @Test
    void pricedOffersCarryDisplayedFareAndEarnings() {
        org.mockito.Mockito.when(quoter.quoterFor("t1", com.voyra.crm.enums.PricingProduct.FLIGHT)).thenReturn(java.util.Optional.of(
                (gross, base) -> new com.voyra.crm.service.booking.PriceQuote(gross.subtract(BigDecimal.TEN), BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "P1", BigDecimal.ONE, BigDecimal.ONE)));
        FlightSearchService withBase = new FlightSearchService(q -> {
            FlightOfferResponse x = FlightOfferResponse.builder().offerId("a").airline("A").flightNumber("F1")
                    .price(new BigDecimal("100")).totalPrice(new BigDecimal("200")).baseFare(new BigDecimal("170")).build();
            return new ArrayList<>(List.of(x));
        }, quoter);
        loginAsTenant("t1");
        FlightOfferResponse o = withBase.search(new FlightSearchQuery()).get(0);
        assertThat(o.isBookable()).isTrue();
        assertThat(o.getPublishedFare()).isEqualByComparingTo("200");
        assertThat(o.getDisplayedFare()).isEqualByComparingTo("190");
        assertThat(o.getAgencyEarns()).isEqualByComparingTo("1");
    }

    @Test
    void platformAdminSearchIsUndecorated() {
        CustomUserPrincipal admin = new CustomUserPrincipal("root", "root", UserType.SUPER_ADMIN, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, java.util.List.of()));
        FlightOfferResponse o = firstFare(service.searchPage(request(1)));
        assertThat(o.getPublishedFare()).isNull();
        assertThat(o.isBookable()).isFalse();
        assertThat(o.getNotBookableReason()).isNull();
        org.mockito.Mockito.verifyNoInteractions(quoter);
    }

    private static com.voyra.crm.service.booking.PriceQuote quoteOf(BigDecimal displayed) {
        return new com.voyra.crm.service.booking.PriceQuote(displayed, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, "P1", BigDecimal.ONE, BigDecimal.ONE);
    }

    private FlightSearchService withBase(BigDecimal base) {
        return new FlightSearchService(q -> new ArrayList<>(List.of(FlightOfferResponse.builder().offerId("a").airline("A")
                .flightNumber("F1").price(new BigDecimal("100")).totalPrice(new BigDecimal("200")).baseFare(base).build())), quoter);
    }

    @Test
    void zeroOrMissingBaseFareIsNotBookable() {
        org.mockito.Mockito.when(quoter.quoterFor("t1", com.voyra.crm.enums.PricingProduct.FLIGHT)).thenReturn(java.util.Optional.of(
                (gross, base) -> quoteOf(gross)));
        loginAsTenant("t1");
        for (BigDecimal base : new BigDecimal[]{BigDecimal.ZERO, null}) {
            FlightOfferResponse o = withBase(base).search(new FlightSearchQuery()).get(0);
            assertThat(o.isBookable()).isFalse();
            assertThat(o.getNotBookableReason()).isEqualTo("BASE_FARE_UNAVAILABLE");
        }
    }

    @Test
    void twoTenantsGetTheirOwnPricesFromTheSameSearch() {
        org.mockito.Mockito.when(quoter.quoterFor("t1", com.voyra.crm.enums.PricingProduct.FLIGHT)).thenReturn(java.util.Optional.of(
                (gross, base) -> quoteOf(new BigDecimal("190"))));
        org.mockito.Mockito.when(quoter.quoterFor("t2", com.voyra.crm.enums.PricingProduct.FLIGHT)).thenReturn(java.util.Optional.of(
                (gross, base) -> quoteOf(new BigDecimal("170"))));
        FlightSearchService svc = withBase(new BigDecimal("170"));
        loginAsTenant("t1");
        assertThat(firstFare(svc.searchPage(request(1))).getDisplayedFare()).isEqualByComparingTo("190");
        loginAsTenant("t2");
        assertThat(firstFare(svc.searchPage(request(1))).getDisplayedFare()).isEqualByComparingTo("170");
    }

    @Test
    void roundTripSplitPagesOnwardAndListsEveryReturnWithTheSameFiltersAndSort() {
        // the mock answers a round trip as ONWARD + RETURN (5 flights x 4 fares each way)
        FlightSearchService svc = new FlightSearchService(new MockFlightSearchProvider(), quoter);
        loginAsTenant("t1");
        FlightSearchPageRequest req = request(1);
        req.getQuery().setReturnDate(LocalDate.of(2026, 11, 28));
        req.setPageSize(2);
        req.setStops(0);

        FlightSearchPageResponse p = svc.searchPage(req);

        assertThat(p.getTripType()).isEqualTo(com.voyra.crm.enums.FlightSearchTripType.ROUND_TRIP_SPLIT);
        assertThat(p.getFlights()).hasSize(2).allMatch(g -> g.getDirection() == com.voyra.crm.enums.FlightDirection.ONWARD);
        assertThat(p.getTotalFlights()).isEqualTo(4); // SpiceJet's one-stop flight is filtered out
        assertThat(p.getReturnFlights()).hasSize(4).allMatch(g -> g.getDirection() == com.voyra.crm.enums.FlightDirection.RETURN
                && g.getOrigin().equals("GOI") && g.getStops() == 0);
        assertThat(p.getReturnFlights()).extracting(g -> g.getCheapestPrice()).isSorted();
        // the same physical template flies both ways, but the two groups never merge
        assertThat(p.getReturnFlights()).extracting(g -> g.getKey()).doesNotContainAnyElementsOf(
                p.getFlights().stream().map(g -> g.getKey()).toList());
    }

    @Test
    void comboFaresAreOneListAndOneWayHasNoReturns() {
        FlightOfferResponse combo = FlightOfferResponse.builder().offerId("c1").airline("IndiGo").flightNumber("6E-1")
                .departTime(LocalTime.of(10, 0)).arriveTime(LocalTime.of(12, 0)).price(new BigDecimal("20000"))
                .direction(com.voyra.crm.enums.FlightDirection.COMBO)
                .legs(List.of(com.voyra.crm.dto.FlightLegResponse.builder().flightNumber("6E-1").build(),
                        com.voyra.crm.dto.FlightLegResponse.builder().flightNumber("6E-2").departTime(LocalTime.of(9, 0)).build()))
                .build();
        FlightSearchService svc = new FlightSearchService(q -> new ArrayList<>(List.of(combo)), quoter);
        loginAsTenant("t1");
        FlightSearchPageRequest req = request(1);
        req.getQuery().setReturnDate(LocalDate.of(2026, 11, 28));

        FlightSearchPageResponse p = svc.searchPage(req);
        assertThat(p.getTripType()).isEqualTo(com.voyra.crm.enums.FlightSearchTripType.ROUND_TRIP_COMBO);
        assertThat(p.getFlights()).singleElement().satisfies(g -> assertThat(g.getLegs()).hasSize(2));
        assertThat(p.getReturnFlights()).isEmpty();

        FlightSearchPageResponse oneWay = service.searchPage(request(1));
        assertThat(oneWay.getTripType()).isEqualTo(com.voyra.crm.enums.FlightSearchTripType.ONE_WAY);
        assertThat(oneWay.getReturnFlights()).isEmpty();
    }

    @Test
    void returnBeforeDepartureIsRefused() {
        FlightSearchQuery q = request(1).getQuery();
        q.setReturnDate(q.getDepartDate().minusDays(1));
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(q)).extracting(v -> v.getMessage()).contains("Return can't be before departure");
        q.setReturnDate(q.getDepartDate());
        assertThat(validator.validate(q)).extracting(v -> v.getMessage()).doesNotContain("Return can't be before departure");
    }
}
