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
                        .offerId("o" + i + "-" + fare).airline(i % 2 == 0 ? "IndiGo" : "Air India")
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

    private final FlightSearchService service = new FlightSearchService(provider);

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
        r.setMaxStops(0);
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
}
