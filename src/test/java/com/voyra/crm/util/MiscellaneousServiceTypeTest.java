package com.voyra.crm.util;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Miscellaneous service type (owner ruling 2026-11-20) through every helper that switches on a type. */
class MiscellaneousServiceTypeTest {

    private static LeadService misc(String details) {
        return LeadService.builder().id("S1").leadId("L1").clientId("C1").type(ServiceType.MISCELLANEOUS)
                .miscDetails(details).leadDestination("Dubai").build();
    }

    @Test
    void itIsItsOwnBookingTypeBothWays() {
        assertThat(ServiceBookingTypeMapper.forService(ServiceType.MISCELLANEOUS)).isEqualTo(BookingType.MISCELLANEOUS);
        assertThat(ServiceBookingTypeMapper.forBooking(BookingType.MISCELLANEOUS)).isEqualTo(ServiceType.MISCELLANEOUS);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.MISCELLANEOUS)).isEqualTo(BookingDocumentType.OTHER);
    }

    @Test
    void aLoggedBookingTakesTheLeadDestinationTheTravellersAndKeepsTheReferenceAsItsPnr() {
        BookingLogRequest log = new BookingLogRequest();
        log.setReference(" INS-778 ");
        log.setNetCost(new BigDecimal("900"));
        log.setSellingPrice(new BigDecimal("1000"));
        LeadMember traveller = LeadMember.builder().id("M1").memberName("Asha").status(LeadMemberStatus.CONFIRMED).build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log, misc("Travel insurance"), List.of(traveller), "Acko");

        assertThat(req.getType()).isEqualTo(BookingType.MISCELLANEOUS);
        assertThat(req.getDestination()).isEqualTo("Dubai");
        assertThat(req.getPnr()).isEqualTo("INS-778");
        assertThat(req.getJourneyDate()).isNull();
        assertThat(req.getPassengers()).extracting("passengerName").containsExactly("Asha");
    }

    @Test
    void itHasNoDates() {
        LeadService s = misc("Forex");
        s.setDateFrom(LocalDate.of(2026, 1, 1));
        s.setDateTo(LocalDate.of(2026, 1, 2));
        ServiceDateRangeDeriver.derive(s);
        assertThat(s.getDateFrom()).isNull();
        assertThat(s.getDateTo()).isNull();
    }

    @Test
    void twoAreToldApartByTheirDetailsCutAtFortyCharacters() {
        LeadService insurance = misc("Travel insurance");
        LeadService longOne = misc("Forex card for the whole family, loaded in AED and USD");
        LeadService blank = misc(null);
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(insurance, longOne, blank)));
        assertThat(insurance.getLabel()).isEqualTo("Miscellaneous — Travel insurance");
        assertThat(longOne.getLabel()).isEqualTo("Miscellaneous — Forex card for the whole family, loaded…");
        assertThat(blank.getLabel()).isEqualTo("Miscellaneous 3");

        LeadService only = misc("SIM card");
        ServiceLabelDeriver.deriveAll(new ArrayList<>(List.of(only)));
        assertThat(only.getLabel()).isEqualTo("Miscellaneous");
    }
}
