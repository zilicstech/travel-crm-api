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

    @Test
    void eachOfferOpensAHotelPageWithItsPhotosAndThreeRoomOptions() {
        HotelOfferResponse offer = provider.search(query(1)).get(0);
        assertThat(offer.getHotelId()).isNotBlank();
        // The photo follows the list, as with the real supplier.
        assertThat(offer.getImageUrl()).isNull();
        assertThat(provider.thumbnails(List.of(offer.getHotelId(), "unknown")))
                .singleElement().satisfies(t -> assertThat(t.getImageUrl()).isNotBlank());

        com.voyra.crm.dto.HotelDetailResponse d = provider.detail(offer.getHotelId(), query(1));
        assertThat(d.getName()).isEqualTo(offer.getHotelName());
        assertThat(d.getImages()).hasSizeGreaterThan(5);
        assertThat(d.getOptions()).hasSize(3);
        // The searched room is the page's cheapest option at the same price.
        assertThat(d.getOptions().get(0).getPrice()).isEqualByComparingTo(offer.getPrice());
    }

    @Test
    void anUnknownHotelHasNoPage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.detail("mock-gone", query(1)))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class)
                .hasMessage("This hotel is no longer in the search - search again");
    }

    @Test
    void aNonRefundableHotelsStandardRoomSaysSoAndUpgradesStayRefundable() {
        com.voyra.crm.dto.HotelDetailResponse d = provider.detail("mock-ibis-budget", query(0));
        assertThat(d.getOptions().get(0).isRefundable()).isFalse();
        assertThat(d.getOptions().get(0).getCancellationPolicy()).isEqualTo("Non-refundable");
        assertThat(d.getOptions().get(1).getCancellationPolicy()).isEqualTo("Free cancellation until 48 hours before check-in");
        // zero rooms counts as one: 3200 x 3 nights
        assertThat(d.getOptions().get(0).getPrice()).isEqualByComparingTo("9600.00");
        assertThat(d.getOptions().get(0).getBaseFare()).isEqualByComparingTo("7872.00");
    }

    @Test
    void optionTotalPricesAMockOptionAsThePageDoesAndNothingElse() {
        assertThat(MockHotelSearchProvider.optionTotal("mock-ibis-budget", "mock-mock-ibis-budget-suite", 2, 0))
                .isEqualByComparingTo("11520.00");
        assertThat(MockHotelSearchProvider.optionTotal("mock-ibis-budget", "mock-mock-ibis-budget-up", 1, 2))
                .isEqualByComparingTo("8640.00");
        assertThat(MockHotelSearchProvider.optionTotal("mock-ibis-budget", "mock-mock-ibis-budget-penthouse", 1, 1)).isNull();
        assertThat(MockHotelSearchProvider.optionTotal("mock-ibis-budget", "other-option", 1, 1)).isNull();
        assertThat(MockHotelSearchProvider.optionTotal("mock-ibis-budget", null, 1, 1)).isNull();
        assertThat(MockHotelSearchProvider.optionTotal("mock-gone", "mock-gone-std", 1, 1)).isNull();
    }
}
