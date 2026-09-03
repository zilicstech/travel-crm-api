package com.voyra.crm.util;

import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-type date derivation, docs/LLD_LEAD_MANAGEMENT.md §4.5. */
class ServiceDateRangeDeriverTest {

    @Test
    void flightDateRangeIsTheEarliestAndLatestSectorDate() {
        LeadService flight = LeadService.builder().type(ServiceType.FLIGHT)
                .flightSectors(List.of(
                        FlightSectorDto.builder().from("BOM").to("BKK").date(LocalDate.of(2026, 10, 1)).build(),
                        FlightSectorDto.builder().from("BKK").to("BOM").date(LocalDate.of(2026, 10, 8)).build()))
                .build();

        ServiceDateRangeDeriver.derive(flight);

        assertThat(flight.getDateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(flight.getDateTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void flightWithNoSectorsYetHasNoDateRange() {
        LeadService flight = LeadService.builder().type(ServiceType.FLIGHT).flightSectors(List.of()).build();
        ServiceDateRangeDeriver.derive(flight);
        assertThat(flight.getDateFrom()).isNull();
        assertThat(flight.getDateTo()).isNull();
    }

    @Test
    void hotelUsesCheckInAndCheckOutWhenBothAreSet() {
        LeadService hotel = LeadService.builder().type(ServiceType.HOTEL)
                .hotelCheckIn(LocalDate.of(2026, 10, 1)).hotelCheckOut(LocalDate.of(2026, 10, 5)).build();

        ServiceDateRangeDeriver.derive(hotel);

        assertThat(hotel.getDateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(hotel.getDateTo()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void hotelFallsBackToCheckInPlusNightsWhenCheckOutIsMissing() {
        LeadService hotel = LeadService.builder().type(ServiceType.HOTEL)
                .hotelCheckIn(LocalDate.of(2026, 10, 1)).hotelNights(4).build();

        ServiceDateRangeDeriver.derive(hotel);

        assertThat(hotel.getDateTo()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void hotelWithOnlyCheckInFallsBackToASingleDayWindow() {
        LeadService hotel = LeadService.builder().type(ServiceType.HOTEL)
                .hotelCheckIn(LocalDate.of(2026, 10, 1)).build();

        ServiceDateRangeDeriver.derive(hotel);

        assertThat(hotel.getDateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(hotel.getDateTo()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void visaUsesTheIntendedTravelDateForBothEnds() {
        LeadService visa = LeadService.builder().type(ServiceType.VISA)
                .visaIntendedTravelDate(LocalDate.of(2026, 11, 1)).build();

        ServiceDateRangeDeriver.derive(visa);

        assertThat(visa.getDateFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(visa.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void transferUsesTheTransferDateForBothEnds() {
        LeadService transfer = LeadService.builder().type(ServiceType.TRANSFER)
                .transferDate(LocalDate.of(2026, 10, 3)).build();

        ServiceDateRangeDeriver.derive(transfer);

        assertThat(transfer.getDateFrom()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(transfer.getDateTo()).isEqualTo(LocalDate.of(2026, 10, 3));
    }
}
