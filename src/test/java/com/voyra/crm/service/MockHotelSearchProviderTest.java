package com.voyra.crm.service;

import com.voyra.crm.dto.HotelOfferResponse;
import com.voyra.crm.dto.HotelSearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic per query so a demo or a test can assert on it - mirrors what MockFlightSearchProvider never got. */
class MockHotelSearchProviderTest {

    private final MockHotelSearchProvider provider = new MockHotelSearchProvider();

    private HotelSearchQuery query(int rooms) {
        HotelSearchQuery query = new HotelSearchQuery();
        query.setCity("Phuket");
        query.setCheckIn(LocalDate.of(2026, 10, 9));
        query.setCheckOut(LocalDate.of(2026, 10, 12));
        query.setRooms(rooms);
        query.setAdults(2);
        return query;
    }

    @Test
    void search_returnsTheSameOffersForTheSameQuery() {
        List<HotelOfferResponse> first = provider.search(query(1));
        List<HotelOfferResponse> second = provider.search(query(1));

        assertThat(first).hasSize(4);
        assertThat(first).usingRecursiveComparison().isEqualTo(second);
    }

    @Test
    void search_pricesScaleWithNightsAndRooms() {
        HotelOfferResponse oneRoom = provider.search(query(1)).get(0);
        HotelOfferResponse twoRooms = provider.search(query(2)).get(0);

        assertThat(oneRoom.getNights()).isEqualTo(3);
        assertThat(twoRooms.getPrice()).isEqualByComparingTo(oneRoom.getPrice().multiply(java.math.BigDecimal.valueOf(2)));
    }

    @Test
    void search_echoesTheRequestedCity() {
        List<HotelOfferResponse> offers = provider.search(query(1));
        assertThat(offers).allMatch(o -> o.getCity().equals("Phuket"));
    }
}
