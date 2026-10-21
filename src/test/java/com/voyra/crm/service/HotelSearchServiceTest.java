package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchPageResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotelSearchServiceTest {

    private final HotelSearchProvider provider = mock(HotelSearchProvider.class);
    private final HotelSearchService service = new HotelSearchService(provider);

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
    void anUnknownSearchIdSearchesAgain() {
        TenantContext.setTenantId("t1");
        when(provider.search(any())).thenReturn(List.of());
        assertThat(service.search(query("gone", 3)).getOffers()).isEmpty();
        verify(provider).search(any());
    }
}
