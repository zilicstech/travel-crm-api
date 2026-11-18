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

    private static HotelOfferResponse hotel(String id, String name, int stars, String price, boolean refundable, String board) {
        return HotelOfferResponse.builder().hotelId(id).hotelName(name).starRating(stars)
                .price(price == null ? null : new BigDecimal(price)).refundable(refundable).boardBasis(board).build();
    }

    private List<String> ids(HotelSearchQuery q) {
        return service.search(q).getOffers().stream().map(HotelOfferResponse::getHotelId).toList();
    }

    @Test
    void filtersNarrowTheKeptListWithoutAskingTheSupplierAgain() {
        when(provider.search(any(), any())).thenReturn(List.of(
                hotel("a", "Sea Breeze", 3, "100", true, "Breakfast"),
                hotel("b", "Hill View", 5, "300", false, "Room Only"),
                hotel("c", null, 4, "200", true, null),
                hotel("d", "Sea Cliff", 0, null, false, "Half Board")));
        HotelSearchPageResponse first = service.search(query(null, 0));
        String sid = first.getSearchId();

        assertThat(first.getTotal()).isEqualTo(4);
        assertThat(first.getPriceMin()).isEqualByComparingTo("100");
        assertThat(first.getPriceMax()).isEqualByComparingTo("300");
        assertThat(first.getBoardBases()).containsExactly("Breakfast", "Half Board", "Room Only");

        HotelSearchQuery q = query(sid, 0);
        q.setName("  SEA ");
        HotelSearchPageResponse named = service.search(q);
        assertThat(named.getOffers()).extracting(HotelOfferResponse::getHotelId).containsExactly("s1:a", "s1:d");
        assertThat(named.getTotal()).isEqualTo(2);
        // facets describe the whole search, not the filtered one
        assertThat(named.getPriceMax()).isEqualByComparingTo("300");
        assertThat(named.getBoardBases()).hasSize(3);

        q = query(sid, 0);
        q.setName(" ");
        q.setStars(List.of());
        q.setBoardBasis(List.of());
        q.setRefundableOnly(false);
        assertThat(ids(q)).hasSize(4);

        q = query(sid, 0);
        q.setStars(List.of(4, 5));
        assertThat(ids(q)).containsExactly("s1:c", "s1:b");

        q = query(sid, 0);
        q.setMinPrice(new BigDecimal("200"));
        assertThat(ids(q)).containsExactly("s1:c", "s1:b");

        q = query(sid, 0);
        q.setMaxPrice(new BigDecimal("200"));
        assertThat(ids(q)).containsExactly("s1:a", "s1:c");

        q = query(sid, 0);
        q.setRefundableOnly(true);
        assertThat(ids(q)).containsExactly("s1:a", "s1:c");

        q = query(sid, 0);
        q.setBoardBasis(List.of("room only", "BREAKFAST"));
        assertThat(ids(q)).containsExactly("s1:a", "s1:b");

        q = query(sid, 0);
        q.setName("sea");
        q.setStars(List.of(5));
        HotelSearchPageResponse none = service.search(q);
        assertThat(none.getOffers()).isEmpty();
        assertThat(none.getTotal()).isZero();
        assertThat(none.getSearchId()).isEqualTo(sid);

        verify(provider, times(1)).search(any(), any());
    }

    /** Property type matches any ticked type; amenities must all be there; the facets describe the whole search. */
    @Test
    void propertyTypeAndAmenityFiltersAndTheirFacets() {
        HotelOfferResponse resort = hotel("a", "Sea Breeze", 4, "100", true, "Breakfast");
        resort.setPropertyType("Resort");
        resort.setAmenities(List.of("Swimming Pool", "Free WiFi", "Spa", "Free WiFi"));
        HotelOfferResponse hotelB = hotel("b", "Hill View", 3, "200", true, "Room Only");
        hotelB.setPropertyType("Hotel");
        hotelB.setAmenities(List.of("Free WiFi", "Gym"));
        HotelOfferResponse unknown = hotel("c", "No Content", 0, "300", true, "Room Only"); // its content did not arrive
        when(provider.search(any(), any())).thenReturn(List.of(resort, hotelB, unknown));

        HotelSearchPageResponse first = service.search(query(null, 0));
        String sid = first.getSearchId();
        assertThat(first.getPropertyTypes()).containsExactly("Hotel", "Resort");
        assertThat(first.getAmenities()).containsExactly("Free WiFi", "Gym", "Spa", "Swimming Pool");

        HotelSearchQuery q = query(sid, 0);
        q.setPropertyTypes(List.of("resort", "VILLA"));
        assertThat(ids(q)).containsExactly("s1:a");

        q = query(sid, 0);
        q.setAmenities(List.of("free wifi"));
        assertThat(ids(q)).containsExactly("s1:a", "s1:b");

        q = query(sid, 0);
        q.setAmenities(List.of("Free WiFi", "Spa"));
        assertThat(ids(q)).containsExactly("s1:a");

        q = query(sid, 0);
        q.setPropertyTypes(List.of());
        q.setAmenities(List.of());
        assertThat(ids(q)).hasSize(3);
    }

    @Test
    void starSortRunsOverTheWholeFilteredListThenCheapestAndPagesAfterIt() {
        when(provider.search(any(), any())).thenReturn(IntStream.range(0, 25)
                .mapToObj(i -> hotel("h" + i, "Hotel " + i, i % 5 + 1, String.valueOf(100 + i), true, "Breakfast")).toList());
        HotelSearchQuery q = query(null, 0);
        q.setSort(com.voyra.crm.enums.HotelSortOrder.STARS);
        HotelSearchPageResponse first = service.search(q);
        assertThat(first.getOffers()).extracting(HotelOfferResponse::getHotelId)
                .startsWith("s1:h4", "s1:h9", "s1:h14", "s1:h19", "s1:h24", "s1:h3");

        q = query(first.getSearchId(), 1);
        q.setSort(com.voyra.crm.enums.HotelSortOrder.STARS);
        assertThat(ids(q)).containsExactly("s1:h0", "s1:h5", "s1:h10", "s1:h15", "s1:h20");

        q = query(first.getSearchId(), 0);
        q.setSort(com.voyra.crm.enums.HotelSortOrder.PRICE);
        assertThat(ids(q)).startsWith("s1:h0", "s1:h1");
    }

    @Test
    void aSearchWithNoPricedHotelHasNoPriceRange() {
        when(provider.search(any(), any())).thenReturn(List.of(hotel("a", "Sea Breeze", 3, null, true, null)));
        HotelSearchPageResponse page = service.search(query(null, 0));
        assertThat(page.getPriceMin()).isNull();
        assertThat(page.getPriceMax()).isNull();
        assertThat(page.getBoardBases()).isEmpty();
        assertThat(page.getPropertyTypes()).isEmpty();
        assertThat(page.getAmenities()).isEmpty();
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

    @Test
    void aHotelPickedByNameAsksOnlyItsSupplierWithItsOwnIdAndKeepsItsOwnResult() {
        when(registry.searchable(PricingProduct.HOTEL)).thenReturn(List.of(S1, S2));
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        HotelSearchQuery one = query(null, 0);
        one.setHotelId("s2:h9");

        HotelSearchPageResponse page = service.search(one);

        assertThat(page.getOffers()).extracting(HotelOfferResponse::getSupplierId).containsExactly("s2");
        verify(provider).search(argThat(q -> "h9".equals(q.getHotelId()) && "Goa".equals(q.getCity())), eq(S2));
        assertThat(one.getHotelId()).isEqualTo("s2:h9");

        // The kept one-hotel result never answers a city search with its searchId, nor the reverse.
        HotelSearchQuery city = query(page.getSearchId(), 0);
        assertThat(service.search(city).getSearchId()).isNotEqualTo(page.getSearchId());
        HotelSearchQuery again = query(page.getSearchId(), 0);
        again.setHotelId("s2:h9");
        assertThat(service.search(again).getSearchId()).isEqualTo(page.getSearchId());
        verify(provider, times(3)).search(any(), any()); // the hotel once, the city from both suppliers, the hotel again from its kept result
    }

    @Test
    void aBlankHotelIdIsACitySearchAndAnUnknownOrSwitchedOffSupplierIsUnavailable() {
        when(provider.search(any(), any())).thenAnswer(i -> new java.util.ArrayList<>(List.of(offer(new BigDecimal("160")))));
        HotelSearchQuery blank = query(null, 0);
        blank.setHotelId(" ");
        service.search(blank);
        assertThat(blank.getHotelId()).isNull();

        for (String id : List.of("no-namespace", "s9:h1")) {
            HotelSearchQuery q = query(null, 0);
            q.setHotelId(id);
            assertThatThrownBy(() -> service.search(q)).isInstanceOf(BookingRequestException.class)
                    .extracting("code").isEqualTo("OFFER_UNAVAILABLE");
        }
    }
}
