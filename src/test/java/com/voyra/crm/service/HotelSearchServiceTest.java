package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelDetailResponse;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelThumbnailResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.SuppliersUnavailableException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HotelSearchServiceTest {

    private static final ResolvedCredential S1 = supplier("s1", "Mock supplier 1");
    private static final ResolvedCredential S2 = supplier("s2", "Mock supplier 2");

    private final HotelSearchProvider provider = mock(HotelSearchProvider.class);
    private final SupplierRegistry registry = mock(SupplierRegistry.class);
    private final com.voyra.crm.service.booking.AgencyPriceQuoter quoter = mock(com.voyra.crm.service.booking.AgencyPriceQuoter.class);
    private final HotelSearchService service = new HotelSearchService(registry, new SupplierFanOut(), quoter);

    private static ResolvedCredential supplier(String id, String name) {
        return new ResolvedCredential(id, name, SupplierProvider.MOCK, SupplierEnvironment.MOCK, "mock://local", "", null,
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    @BeforeEach
    void suppliers() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1));
        when(registry.hotelSearch(SupplierProvider.MOCK)).thenReturn(provider);
        when(registry.bookingEnabledIds(PricingProduct.HOTEL)).thenReturn(Set.of("s1"));
    }

    private static com.voyra.crm.service.booking.PriceQuote quoteOf(BigDecimal displayed) {
        return new com.voyra.crm.service.booking.PriceQuote(displayed, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, "P1", BigDecimal.ONE, BigDecimal.ONE);
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
        when(provider.search(any(), any())).thenReturn(new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
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
        when(provider.detail(any(), any(), any())).thenReturn(detailWith(new BigDecimal("160")), detailWith(null), detailWith(BigDecimal.ZERO));
        HotelDetailResponse.RoomOption ok = service.detail("s1:h1", query(null, 0)).getOptions().get(0);
        assertThat(ok.isBookable()).isTrue();
        assertThat(ok.getPublishedFare()).isEqualByComparingTo("200");
        assertThat(ok.getDisplayedFare()).isEqualByComparingTo("190");
        for (int i = 0; i < 2; i++) {
            HotelDetailResponse.RoomOption bad = service.detail("s1:h1", query(null, 0)).getOptions().get(0);
            assertThat(bad.isBookable()).isFalse();
            assertThat(bad.getNotBookableReason()).isEqualTo("BASE_FARE_UNAVAILABLE");
            assertThat(bad.getDisplayedFare()).isEqualByComparingTo("200");
        }
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(provider.detail(any(), any(), any())).thenReturn(detailWith(new BigDecimal("160")));
        assertThat(service.detail("s1:h1", query(null, 0)).getOptions().get(0).getNotBookableReason()).isEqualTo("PRICING_NOT_CONFIGURED");
    }

    @Test
    void platformAdminResultsAreUndecoratedAndTenantsGetTheirOwnPrices() {
        when(provider.search(any(), any())).thenReturn(new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        HotelOfferResponse admin = service.search(query(null, 0)).getOffers().get(0);
        assertThat(admin.getPublishedFare()).isNull();
        verifyNoInteractions(quoter);

        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("190"))));
        when(quoter.quoterFor("t2", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("170"))));
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
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
        when(provider.search(any(), any())).thenReturn(IntStream.range(0, 45)
                .mapToObj(i -> HotelOfferResponse.builder().hotelId("h" + i).price(BigDecimal.valueOf(i)).build()).toList());

        HotelSearchPageResponse first = service.search(query(null, 0));
        HotelSearchPageResponse third = service.search(query(first.getSearchId(), 2));
        HotelSearchPageResponse beyond = service.search(query(first.getSearchId(), 9));

        assertThat(first.getTotal()).isEqualTo(45);
        assertThat(first.getOffers()).hasSize(20).first().extracting(HotelOfferResponse::getHotelId).isEqualTo("s1:h0");
        assertThat(third.getSearchId()).isEqualTo(first.getSearchId());
        assertThat(third.getOffers()).extracting(HotelOfferResponse::getHotelId).containsExactly("s1:h40", "s1:h41", "s1:h42", "s1:h43", "s1:h44");
        assertThat(beyond.getOffers()).isEmpty();
        verify(provider, times(1)).search(any(), any());

        // Another agency quoting the id gets its own search, never the first one's rates.
        TenantContext.setTenantId("t2");
        assertThat(service.search(query(first.getSearchId(), 1)).getSearchId()).isNotEqualTo(first.getSearchId());
        verify(provider, times(2)).search(any(), any());
    }

    @Test
    void aResultIsKeptForThirtyMinutesThenSearchedAgain() {
        TenantContext.setTenantId("t1");
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<HotelOfferResponse>());
        java.time.Instant start = java.time.Instant.parse("2026-10-31T10:00:00Z");
        service.clock = java.time.Clock.fixed(start, java.time.ZoneOffset.UTC);
        String first = service.search(query(null, 0)).getSearchId();

        service.clock = java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(30)), java.time.ZoneOffset.UTC);
        assertThat(service.search(query(first, 1)).getSearchId()).isEqualTo(first);
        verify(provider, times(1)).search(any(), any());

        service.clock = java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(31)), java.time.ZoneOffset.UTC);
        String second = service.search(query(first, 1)).getSearchId();
        assertThat(second).isNotEqualTo(first);
        verify(provider, times(2)).search(any(), any());
        // the new result serves its later pages from memory
        assertThat(service.search(query(second, 1)).getSearchId()).isEqualTo(second);
        verify(provider, times(2)).search(any(), any());
    }

    @Test
    void anUnknownSearchIdSearchesAgain() {
        TenantContext.setTenantId("t1");
        when(provider.search(any(), any())).thenReturn(List.of());
        assertThat(service.search(query("gone", 3)).getOffers()).isEmpty();
        verify(provider).search(any(), any());
    }

    /** Slice 4 T1: a searchId is no key to another agency's priced result - another tenant or a platform admin quoting it searches anew. */
    @Test
    void anotherTenantsSearchIdNeverServesThatTenantsPricedOffers() {
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("190"))));
        when(quoter.quoterFor("t2", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(new BigDecimal("170"))));
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
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
        verify(provider, times(3)).search(any(), any());
    }

    @Test
    void onceFullTheOldestKeptResultMakesWayForTheNewOne() {
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<HotelOfferResponse>());
        String oldest = service.search(query(null, 0)).getSearchId();
        for (int i = 1; i < HotelSearchService.MAX_KEPT; i++) {
            service.search(query(null, 0));
        }
        // still kept: no new supplier call
        assertThat(service.search(query(oldest, 0)).getSearchId()).isEqualTo(oldest);

        service.search(query(null, 0));

        assertThat(service.search(query(oldest, 0)).getSearchId()).isNotEqualTo(oldest);
        verify(provider, times(HotelSearchService.MAX_KEPT + 2)).search(any(), any());
    }

    @Test
    void aDetailForAPlatformAdminOrWithNoOptionsIsReturnedUnpriced() {
        HotelDetailResponse forAdmin = detailWith(new BigDecimal("160"));
        HotelDetailResponse noOptions = HotelDetailResponse.builder().hotelId("h1").build();
        when(provider.detail(any(), any(), any())).thenReturn(forAdmin, noOptions);

        assertThat(service.detail("s1:h1", query(null, 0)).getOptions().get(0).getPublishedFare()).isNull();
        TenantContext.setTenantId("t1");
        assertThat(service.detail("s1:h1", query(null, 0)).getOptions()).isNull();
        verifyNoInteractions(quoter);
    }

    @Test
    void anOfferWithNoPriceIsNotBookable() {
        TenantContext.setTenantId("t1");
        when(quoter.quoterFor("t1", PricingProduct.HOTEL)).thenReturn(Optional.of((g, b) -> quoteOf(g)));
        HotelOfferResponse unpriced = HotelOfferResponse.builder().hotelId("h1").price(null).baseFare(new BigDecimal("100")).build();
        when(provider.search(any(), any())).thenReturn(new java.util.ArrayList<>(List.of(unpriced)));

        HotelOfferResponse o = service.search(query(null, 0)).getOffers().get(0);

        assertThat(o.isBookable()).isFalse();
        assertThat(o.getNotBookableReason()).isEqualTo("BASE_FARE_UNAVAILABLE");
        assertThat(o.getAgencyEarns()).isEqualByComparingTo("0");
    }

    private static HotelOfferResponse offerOf(String hotelId, String price) {
        return HotelOfferResponse.builder().hotelId(hotelId).offerId("o-" + hotelId).correlationId("c1").price(new BigDecimal(price)).build();
    }

    private static HotelSearchQuery detailQuery(String correlationId) {
        HotelSearchQuery q = query(null, 0);
        q.setCorrelationId(correlationId);
        return q;
    }

    @Test
    void hotelsFromTwoSuppliersAreSeparateRowsCheapestFirst() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        when(provider.search(any(), eq(S1))).thenReturn(List.of(offerOf("h1", "300")));
        when(provider.search(any(), eq(S2))).thenReturn(List.of(offerOf("h1", "200")));

        List<HotelOfferResponse> offers = service.search(query(null, 0)).getOffers();

        assertThat(offers).extracting(HotelOfferResponse::getHotelId, HotelOfferResponse::getOfferId, HotelOfferResponse::getCorrelationId,
                        HotelOfferResponse::getSupplierName, HotelOfferResponse::getSupplier, HotelOfferResponse::getSupplierId)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("s2:h1", "s2:o-h1", "s2:c1", "Mock supplier 2", "Mock supplier 2", "s2"),
                        org.assertj.core.api.Assertions.tuple("s1:h1", "s1:o-h1", "s1:c1", "Mock supplier 1", "Mock supplier 1", "s1"));
    }

    @Test
    void noSearchableHotelSupplierIs409() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of());
        assertThatThrownBy(() -> service.search(query(null, 0))).isInstanceOf(BookingConflictException.class)
                .hasMessage("No supplier is enabled for hotel search");
    }

    @Test
    void everySupplierFailingIs503() {
        when(provider.search(any(), any())).thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> service.search(query(null, 0))).isInstanceOf(SuppliersUnavailableException.class);
    }

    @Test
    void aPartialSearchIsKeptForPagingWithItsFailedSupplier() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        when(provider.search(any(), eq(S1))).thenReturn(IntStream.range(0, 25)
                .mapToObj(i -> offerOf("h" + i, String.valueOf(100 + i))).toList());
        when(provider.search(any(), eq(S2))).thenThrow(new IllegalStateException("down"));

        HotelSearchPageResponse first = service.search(query(null, 0));
        HotelSearchPageResponse second = service.search(query(first.getSearchId(), 1));

        assertThat(first.getSuppliersFailed()).containsExactly("Mock supplier 2");
        assertThat(second.getSearchId()).isEqualTo(first.getSearchId());
        assertThat(second.getOffers()).hasSize(5);
        assertThat(second.getSuppliersFailed()).containsExactly("Mock supplier 2");
        verify(provider, times(2)).search(any(), any());
    }

    @Test
    void detailGoesToTheHotelsOwnSupplier() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        HotelDetailResponse raw = HotelDetailResponse.builder().hotelId("h1").options(new java.util.ArrayList<>(List.of(
                HotelDetailResponse.RoomOption.builder().optionId("o1").price(new BigDecimal("200")).build()))).build();
        when(provider.detail(eq("h1"), any(), eq(S2))).thenReturn(raw);

        HotelDetailResponse d = service.detail("s2:h1", detailQuery("s2:c9"));

        verify(provider).detail(eq("h1"), argThat(q -> "c9".equals(q.getCorrelationId())), eq(S2));
        assertThat(d.getHotelId()).isEqualTo("s2:h1");
        assertThat(d.getOptions()).extracting(HotelDetailResponse.RoomOption::getOptionId).containsExactly("s2:o1");
        assertThat(d.getSupplierName()).isEqualTo("Mock supplier 2");
        assertThat(d.getSupplier()).isEqualTo("Mock supplier 2");
        assertThat(d.getSupplierId()).isEqualTo("s2");
        assertThat(d.isBookingEnabled()).isFalse(); // only s1 takes bookings
    }

    @Test
    void detailWithoutOptionsOrCorrelationIdStillNamespacesTheHotel() {
        when(provider.detail(eq("h1"), any(), eq(S1))).thenReturn(HotelDetailResponse.builder().hotelId("h1").build());

        HotelDetailResponse d = service.detail("s1:h1", detailQuery(" "));

        assertThat(d.getHotelId()).isEqualTo("s1:h1");
        assertThat(d.isBookingEnabled()).isTrue();
        assertThat(d.getOptions()).isNull();
    }

    @Test
    void detailOfAnUnknownOrUnnamespacedHotelIs400() {
        for (String id : List.of("h1", "s9:h1")) {
            assertThatThrownBy(() -> service.detail(id, detailQuery(null))).isInstanceOf(BookingRequestException.class)
                    .hasMessage("This hotel is no longer available; search again").extracting("code").isEqualTo("OFFER_UNAVAILABLE");
        }
    }

    @Test
    void detailWithACorrelationIdOfAnotherSupplierOrNoSupplierIs400() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        for (String correlation : List.of("s2:c9", "c9")) {
            assertThatThrownBy(() -> service.detail("s1:h1", detailQuery(correlation))).isInstanceOf(BookingRequestException.class)
                    .hasMessage("This hotel is no longer available; search again");
        }
        verifyNoInteractions(provider);
    }

    @Test
    void thumbnailsGoToEachHotelsSupplierAndKeepTheirIds() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        when(provider.thumbnails(eq(List.of("h1")), eq(S1))).thenReturn(List.of(HotelThumbnailResponse.builder().hotelId("h1").build()));
        when(provider.thumbnails(eq(List.of("h7")), eq(S2))).thenReturn(List.of(HotelThumbnailResponse.builder().hotelId("h7").build()));

        assertThat(service.thumbnails(List.of("s1:h1", "s2:h7", "junk", "s9:h3"))).extracting(HotelThumbnailResponse::getHotelId)
                .containsExactlyInAnyOrder("s1:h1", "s2:h7");
    }

    @Test
    void aSupplierWhoseThumbnailsFailContributesNothing() {
        when(provider.thumbnails(any(), any())).thenThrow(new IllegalStateException("down"));
        assertThat(service.thumbnails(List.of("s1:h1"))).isEmpty();
    }
}
