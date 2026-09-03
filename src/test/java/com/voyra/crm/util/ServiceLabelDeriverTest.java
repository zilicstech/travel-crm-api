package com.voyra.crm.util;

import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four-rule naming ladder from docs/LLD_LEAD_MANAGEMENT.md §4.2, ported from the frontend's
 * serviceInstanceLabel so both sides agree on a service's display name.
 */
class ServiceLabelDeriverTest {

    private LeadService flight(String from, String to) {
        return LeadService.builder().type(ServiceType.FLIGHT)
                .flightSectors(from == null ? List.of() : List.of(FlightSectorDto.builder()
                        .from(from).to(to).date(LocalDate.of(2026, 10, 1)).build()))
                .build();
    }

    private LeadService hotel(String city) {
        return LeadService.builder().type(ServiceType.HOTEL).hotelCity(city).build();
    }

    @Test
    void aSingleInstanceOfATypeGetsThePlainTypeLabel() {
        LeadService only = hotel("Phuket");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(only)));
        assertThat(only.getLabel()).isEqualTo("Hotel");
    }

    @Test
    void twoInstancesWithDifferentSourcesEachGetTheirOwnLabelWithNoOrdinal() {
        LeadService bangkok = hotel("Bangkok");
        LeadService phuket = hotel("Phuket");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(bangkok, phuket)));

        assertThat(bangkok.getLabel()).isEqualTo("Hotel — Bangkok");
        assertThat(phuket.getLabel()).isEqualTo("Hotel — Phuket");
    }

    @Test
    void theSecondInstanceSharingASourceGetsAnOrdinalSuffixButTheFirstDoesNot() {
        LeadService first = hotel("Phuket");
        LeadService second = hotel("Phuket");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(first, second)));

        assertThat(first.getLabel()).isEqualTo("Hotel — Phuket");
        assertThat(second.getLabel()).isEqualTo("Hotel — Phuket (2)");
    }

    @Test
    void aBlankSourceFallsBackToAPositionalLabelAmongAllSiblingsOfThatType() {
        LeadService withCity = hotel("Bangkok");
        LeadService blank = hotel(null);
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(withCity, blank)));

        assertThat(withCity.getLabel()).isEqualTo("Hotel — Bangkok");
        assertThat(blank.getLabel()).isEqualTo("Hotel 2");
    }

    /** A round trip's leg 2 mirrors leg 1, so the first sector with both ends set names the flight either way. */
    @Test
    void flightSourceIsTheRouteOfTheFirstSectorWithBothEndsSet() {
        LeadService flight = flight("BOM", "BKK");
        LeadService other = flight("BOM", "DXB");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(flight, other)));

        assertThat(flight.getLabel()).isEqualTo("Flight — BOM–BKK");
        assertThat(other.getLabel()).isEqualTo("Flight — BOM–DXB");
    }

    @Test
    void differentTypesAreDerivedIndependentlyOfOneAnother() {
        LeadService onlyFlight = flight("BOM", "BKK");
        LeadService onlyHotel = hotel("Phuket");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(onlyFlight, onlyHotel)));

        assertThat(onlyFlight.getLabel()).isEqualTo("Flight");
        assertThat(onlyHotel.getLabel()).isEqualTo("Hotel");
    }
}
