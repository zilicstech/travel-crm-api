package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HotelSearchServiceTest {

    private final HotelSearchProvider provider = mock(HotelSearchProvider.class);
    private final com.voyra.crm.service.booking.AgencyPriceQuoter quoter = mock(com.voyra.crm.service.booking.AgencyPriceQuoter.class);
    private final HotelSearchService service = new HotelSearchService(provider, quoter);

    private static com.voyra.crm.service.booking.PriceQuote quoteOf(BigDecimal displayed) {
        return new com.voyra.crm.service.booking.PriceQuote(displayed, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, "P1", BigDecimal.ONE, BigDecimal.ONE);
    }

    private static HotelOfferResponse offer(BigDecimal base) {
        return HotelOfferResponse.builder().hotelId("h1").price(new BigDecimal("200")).baseFare(base).build();
    }

    private static HotelDetailResponse detailWith(BigDecimal base) {
        return HotelDetailResponse.builder().hotelId("h1").options(new java.util.ArrayList<>(List.of(
                HotelDetailResponse.RoomOption.builder().optionId("o1").price(new BigDecimal("200")).baseFare(base).build()))).build();
    }

    @Test
    void searchOffersCarryPublishedAndDisplayedFareAndEarnings() {
        TenantContext.setTenantId("t1");
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((gross, base) -> quoteOf(gross.subtract(BigDecimal.TEN))));
        when(provider.search(any())).thenReturn(new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        HotelOfferResponse o = service.search(query(null, 0)).getOffers().get(0);
        assertThat(o.isBookable()).isTrue();
        assertThat(o.getPublishedFare()).isEqualByComparingTo("200");
        assertThat(o.getDisplayedFare()).isEqualByComparingTo("190");
        assertThat(o.getAgencyEarns()).isEqualByComparingTo("1");
    }

    @Test
    void detailOptionsArePricedAndNoPricingRowOrMissingBaseIsNotBookable() {
        TenantContext.setTenantId("t1");
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((gross, base) -> quoteOf(gross.subtract(BigDecimal.TEN))));
        when(provider.detail(any(), any())).thenReturn(detailWith(new BigDecimal("160")), detailWith(null), detailWith(BigDecimal.ZERO));
        HotelDetailResponse.RoomOption ok = service.detail("h1", query(null, 0)).getOptions().get(0);
        assertThat(ok.isBookable()).isTrue();
        assertThat(ok.getPublishedFare()).isEqualByComparingTo("200");
        assertThat(ok.getDisplayedFare()).isEqualByComparingTo("190");
        for (int i = 0; i < 2; i++) {
            HotelDetailResponse.RoomOption bad = service.detail("h1", query(null, 0)).getOptions().get(0);
            assertThat(bad.isBookable()).isFalse();
            assertThat(bad.getNotBookableReason()).isEqualTo("BASE_FARE_UNAVAILABLE");
            assertThat(bad.getDisplayedFare()).isEqualByComparingTo("200");
        }
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(provider.detail(any(), any())).thenReturn(detailWith(new BigDecimal("160")));
        assertThat(service.detail("h1", query(null, 0)).getOptions().get(0).getNotBookableReason()).isEqualTo("PRICING_NOT_CONFIGURED");
    }

    @Test
    void platformAdminResultsAreUndecoratedAndTenantsGetTheirOwnPrices() {
        when(provider.search(any())).thenReturn(new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        HotelOfferResponse admin = service.search(query(null, 0)).getOffers().get(0);
        assertThat(admin.getPublishedFare()).isNull();
        verifyNoInteractions(quoter);

        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("190"))));
        when(quoter.quoterFor("t2", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("170"))));
        when(provider.search(any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        TenantContext.setTenantId("t1");
        assertThat(service.search(query(null, 0)).getOffers().get(0).getDisplayedFare()).isEqualByComparingTo("190");
        TenantContext.setTenantId("t2");
        assertThat(service.search(query(null, 0)).getOffers().get(0).getDisplayedFare()).isEqualByComparingTo("170");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static HotelSearchQuery query(String searchId, int page) {
        HotelSearchQuery q = new HotelSearchQuery();
        q.setCity("Goa");
        q.setSearchId(searchId);
        q.setPage(page);
        return q;
    }

    @Test
    void laterPagesComeFromTheFirstSearchAndOnlyThatAgencyCanReadThem() {
        TenantContext.setTenantId("t1");
        when(provider.search(any())).thenReturn(IntStream.range(0, 45)
                .mapToObj(i -> HotelOfferResponse.builder().hotelId("h" + i).price(BigDecimal.valueOf(i)).build()).toList());

        HotelSearchPageResponse first = service.search(query(null, 0));
        HotelSearchPageResponse third = service.search(query(first.getSearchId(), 2));
        HotelSearchPageResponse beyond = service.search(query(first.getSearchId(), 9));

        assertThat(first.getTotal()).isEqualTo(45);
        assertThat(first.getOffers()).hasSize(20).first().extracting(HotelOfferResponse::getHotelId).isEqualTo("h0");
        assertThat(third.getSearchId()).isEqualTo(first.getSearchId());
        assertThat(third.getOffers()).extracting(HotelOfferResponse::getHotelId).containsExactly("h40", "h41", "h42", "h43", "h44");
        assertThat(beyond.getOffers()).isEmpty();
        verify(provider, times(1)).search(any());

        // Another agency quoting the id gets its own search, never the first one's rates.
        TenantContext.setTenantId("t2");
        assertThat(service.search(query(first.getSearchId(), 1)).getSearchId()).isNotEqualTo(first.getSearchId());
        verify(provider, times(2)).search(any());
    }

    @Test
    void aResultIsKeptForThirtyMinutesThenSearchedAgain() {
        TenantContext.setTenantId("t1");
        when(provider.search(any())).thenAnswer(i -> new java.util.ArrayList<HotelOfferResponse>());
        java.time.Instant start = java.time.Instant.parse("2026-10-31T10:00:00Z");
        service.clock = java.time.Clock.fixed(start, java.time.ZoneOffset.UTC);
        String first = service.search(query(null, 0)).getSearchId();

        service.clock = java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(30)), java.time.ZoneOffset.UTC);
        assertThat(service.search(query(first, 1)).getSearchId()).isEqualTo(first);
        verify(provider, times(1)).search(any());

        service.clock = java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(31)), java.time.ZoneOffset.UTC);
        String second = service.search(query(first, 1)).getSearchId();
        assertThat(second).isNotEqualTo(first);
        verify(provider, times(2)).search(any());
        // the new result serves its later pages from memory
        assertThat(service.search(query(second, 1)).getSearchId()).isEqualTo(second);
        verify(provider, times(2)).search(any());
    }

    @Test
    void anUnknownSearchIdSearchesAgain() {
        TenantContext.setTenantId("t1");
        when(provider.search(any())).thenReturn(List.of());
        assertThat(service.search(query("gone", 3)).getOffers()).isEmpty();
        verify(provider).search(any());
    }

    /** Slice 4 T1: a searchId is no key to another agency's priced result - another tenant or a platform admin quoting it searches anew. */
    @Test
    void anotherTenantsSearchIdNeverServesThatTenantsPricedOffers() {
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("190"))));
        when(quoter.quoterFor("t2", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("170"))));
        when(provider.search(any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        TenantContext.setTenantId("t1");
        String t1Search = service.search(query(null, 0)).getSearchId();

        TenantContext.setTenantId("t2");
        HotelSearchPageResponse t2 = service.search(query(t1Search, 0));
        assertThat(t2.getSearchId()).isNotEqualTo(t1Search);
        assertThat(t2.getOffers().get(0).getDisplayedFare()).isEqualByComparingTo("170");

        TenantContext.clear();
        HotelSearchPageResponse admin = service.search(query(t1Search, 0));
        assertThat(admin.getSearchId()).isNotEqualTo(t1Search);
        assertThat(admin.getOffers().get(0).getDisplayedFare()).isNull();

        // and t1's own id still serves t1 from its cache
        TenantContext.setTenantId("t1");
        assertThat(service.search(query(t1Search, 0)).getOffers().get(0).getDisplayedFare()).isEqualByComparingTo("190");
        verify(provider, times(3)).search(any());
    }

    @Test
    void onceFullTheOldestKeptResultMakesWayForTheNewOne() {
        when(provider.search(any())).thenAnswer(i -> new java.util.ArrayList<HotelOfferResponse>());
        String oldest = service.search(query(null, 0)).getSearchId();
        for (int i = 1; i < HotelSearchService.MAX_KEPT; i++) {
            service.search(query(null, 0));
        }
        // still kept: no new supplier call
        assertThat(service.search(query(oldest, 0)).getSearchId()).isEqualTo(oldest);

        service.search(query(null, 0));

        assertThat(service.search(query(oldest, 0)).getSearchId()).isNotEqualTo(oldest);
        verify(provider, times(HotelSearchService.MAX_KEPT + 2)).search(any());
    }

    @Test
    void aDetailForAPlatformAdminOrWithNoOptionsIsReturnedUnpriced() {
        HotelDetailResponse forAdmin = detailWith(new BigDecimal("160"));
        HotelDetailResponse noOptions = HotelDetailResponse.builder().hotelId("h1").build();
        when(provider.detail(any(), any())).thenReturn(forAdmin, noOptions);

        assertThat(service.detail("h1", query(null, 0)).getOptions().get(0).getPublishedFare()).isNull();
        TenantContext.setTenantId("t1");
        assertThat(service.detail("h1", query(null, 0)).getOptions()).isNull();
        verifyNoInteractions(quoter);
    }

    @Test
    void anOfferWithNoPriceIsNotBookable() {
        TenantContext.setTenantId("t1");
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(g)));
        HotelOfferResponse unpriced = HotelOfferResponse.builder().hotelId("h1").price(null).baseFare(new BigDecimal("100")).build();
        when(provider.search(any())).thenReturn(new java.util.ArrayList<>(List.of(unpriced)));

        HotelOfferResponse o = service.search(query(null, 0)).getOffers().get(0);

        assertThat(o.isBookable()).isFalse();
        assertThat(o.getNotBookableReason()).isEqualTo("BASE_FARE_UNAVAILABLE");
        assertThat(o.getAgencyEarns()).isEqualByComparingTo("0");
    }
}
