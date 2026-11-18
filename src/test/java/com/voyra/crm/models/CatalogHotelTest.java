package com.voyra.crm.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogHotelTest {

    @Test
    void textIsTrimmedBlankBecomesNullLongIsCutAndOddStarsAreDropped() {
        CatalogHotel h = new CatalogHotel(" 1 ", "  " + "n".repeat(250), "Goa", " ", null, 6, "p".repeat(70));
        assertThat(h.hotelId()).isEqualTo("1");
        assertThat(h.name()).hasSize(200);
        assertThat(h.countryCode()).isNull();
        assertThat(h.countryName()).isNull();
        assertThat(h.starRating()).isNull();
        assertThat(h.propertyType()).hasSize(60);
        assertThat(new CatalogHotel("1", "A", "B", "IN", "India", 0, null).starRating()).isNull();
        assertThat(new CatalogHotel("1", "A", "B", "IN", "India", 5, null).starRating()).isEqualTo(5);
        assertThat(new CatalogHotel("1", "A", "B", "IN", "India", null, null).starRating()).isNull();
    }
}
