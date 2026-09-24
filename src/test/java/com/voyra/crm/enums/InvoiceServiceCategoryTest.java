package com.voyra.crm.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceServiceCategoryTest {

    @Test
    void flightSplitsOnTheInternationalFlag() {
        assertThat(InvoiceServiceCategory.forBooking(BookingType.FLIGHT, true))
                .isEqualTo(InvoiceServiceCategory.AIR_INTERNATIONAL);
        assertThat(InvoiceServiceCategory.forBooking(BookingType.FLIGHT, false))
                .isEqualTo(InvoiceServiceCategory.AIR_DOMESTIC);
    }

    @Test
    void everyOtherBookingTypeMapsOneToOne() {
        assertThat(InvoiceServiceCategory.forBooking(BookingType.HOTEL, false)).isEqualTo(InvoiceServiceCategory.HOTEL);
        assertThat(InvoiceServiceCategory.forBooking(BookingType.VISA, false)).isEqualTo(InvoiceServiceCategory.VISA);
        assertThat(InvoiceServiceCategory.forBooking(BookingType.TRANSFER, false)).isEqualTo(InvoiceServiceCategory.TRANSPORT);
        assertThat(InvoiceServiceCategory.forBooking(BookingType.PACKAGE, false)).isEqualTo(InvoiceServiceCategory.PACKAGE);
    }

    @Test
    void nullBookingTypeFallsBackToMiscellaneous() {
        assertThat(InvoiceServiceCategory.forBooking(null, false)).isEqualTo(InvoiceServiceCategory.MISCELLANEOUS);
    }

    @Test
    void railAndMiscellaneousAreNeverDerivedFromABooking() {
        for (BookingType type : BookingType.values()) {
            assertThat(InvoiceServiceCategory.forBooking(type, true)).isNotEqualTo(InvoiceServiceCategory.RAIL);
            assertThat(InvoiceServiceCategory.forBooking(type, true)).isNotEqualTo(InvoiceServiceCategory.MISCELLANEOUS);
        }
    }

    @Test
    void everyCategoryHasAUniquePrefix() {
        long distinctPrefixes = java.util.Arrays.stream(InvoiceServiceCategory.values())
                .map(InvoiceServiceCategory::numberPrefix).distinct().count();
        assertThat(distinctPrefixes).isEqualTo(InvoiceServiceCategory.values().length);
    }
}
