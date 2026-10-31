package com.voyra.crm.util;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingLogRequestMapperCoverageTest {

    private static BookingLogRequest log() {
        BookingLogRequest r = new BookingLogRequest();
        r.setReference(" REF1 ");
        r.setVendorId(" ");
        r.setNetCost(new BigDecimal("900"));
        r.setSellingPrice(new BigDecimal("1000"));
        r.setNotes(" ");
        r.setAgentId("AG-REQ");
        return r;
    }

    private static BookingLogRequest adHoc(BookingType type) {
        BookingLogRequest r = log();
        r.setClientId("C1");
        r.setType(type);
        r.setDestination(" Goa ");
        return r;
    }

    private static final LeadMember PAX = LeadMember.builder().id("M1").memberName("Pax One").status(LeadMemberStatus.CONFIRMED).build();

    @Test
    void aMarkupWorksOutTheSellingPriceAndIsCarriedOver() {
        BookingLogRequest r = adHoc(BookingType.PACKAGE);
        r.setSellingPrice(null);
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("10"));
        r.setVendorId("V1");
        r.setNotes("Note");

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(r, null, List.of(), "Vendor");

        assertThat(req.getSellingPrice()).isEqualByComparingTo("990");
        assertThat(req.getMarkupMode()).isEqualTo(MarkupMode.PERCENT);
        assertThat(req.getMarkupValue()).isEqualByComparingTo("10");
        assertThat(req.getVendorId()).isEqualTo("V1");
        assertThat(req.getNotes()).isEqualTo("Note");
    }

    @Test
    void noSellingPriceAndNoMarkupIsRefused() {
        BookingLogRequest r = adHoc(BookingType.PACKAGE);
        r.setSellingPrice(null);

        assertThatThrownBy(() -> BookingLogRequestMapper.toCreateRequest(r, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Add the markup - the selling price is worked out from the cost and markup");
    }

    @Test
    void anAdHocBookingNeedsATypeAndADestination() {
        BookingLogRequest noType = adHoc(null);
        BookingLogRequest noDestination = adHoc(BookingType.PACKAGE);
        noDestination.setDestination(" ");

        assertThatThrownBy(() -> BookingLogRequestMapper.toCreateRequest(noType, null, List.of(), null))
                .hasMessage("Type is required");
        assertThatThrownBy(() -> BookingLogRequestMapper.toCreateRequest(noDestination, null, List.of(), null))
                .hasMessage("Destination is required");
    }

    @Test
    void anAdHocFlightTakesItsTripTypeFromTheDates() {
        BookingLogRequest round = adHoc(BookingType.FLIGHT);
        round.setJourneyDate(LocalDate.of(2026, 1, 1));
        round.setReturnDate(LocalDate.of(2026, 1, 5));
        round.setInternationalTrip(true);
        BookingLogRequest oneWay = adHoc(BookingType.FLIGHT);
        oneWay.setJourneyDate(LocalDate.of(2026, 1, 1));
        BookingLogRequest undated = adHoc(BookingType.FLIGHT);

        BookingCreateRequest r1 = BookingLogRequestMapper.toCreateRequest(round, null, List.of(), null);

        assertThat(r1.getTripType()).isEqualTo("Round Trip");
        assertThat(r1.getInternationalTrip()).isTrue();
        assertThat(r1.getPnr()).isEqualTo("REF1");
        assertThat(r1.getVendorId()).isNull();
        assertThat(r1.getNotes()).isNull();
        assertThat(BookingLogRequestMapper.toCreateRequest(oneWay, null, List.of(), null).getTripType()).isEqualTo("One Way");
        assertThat(BookingLogRequestMapper.toCreateRequest(undated, null, List.of(), null).getTripType()).isNull();
    }

    @Test
    void anAdHocHotelHasNoTripType() {
        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(adHoc(BookingType.HOTEL), null, List.of(), null);

        assertThat(req.getTripType()).isNull();
        assertThat(req.getHotelConfirmationNo()).isEqualTo("REF1");
    }

    @Test
    void eachBookingTypeHasItsVoucherType() {
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.FLIGHT)).isEqualTo(BookingDocumentType.E_TICKET);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.HOTEL)).isEqualTo(BookingDocumentType.HOTEL_VOUCHER);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.VISA)).isEqualTo(BookingDocumentType.VISA_COPY);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.TRANSFER)).isEqualTo(BookingDocumentType.TRANSFER_VOUCHER);
        assertThat(BookingLogRequestMapper.voucherTypeFor(BookingType.PACKAGE)).isEqualTo(BookingDocumentType.OTHER);
    }

    @Test
    void aOneWayFlightServiceTakesOnlyTheFirstSectorAndNoReturn() {
        LeadService s = LeadService.builder().id("S1").type(ServiceType.FLIGHT).flightTripType(FlightTripType.ONE_WAY)
                .flightCabin(FlightCabin.ECONOMY).flightSectors(List.of(
                        new FlightSectorDto("AAA", "BBB", LocalDate.of(2026, 2, 1)),
                        new FlightSectorDto("BBB", "AAA", LocalDate.of(2026, 2, 9)))).build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(), s, List.of(PAX), null);

        assertThat(req.getTripType()).isEqualTo("One Way");
        assertThat(req.getReturnDate()).isNull();
        assertThat(req.getFlightCabin()).isEqualTo("Economy");
        assertThat(req.getAgentId()).isNull(); // the caller owns a service booking, set by BookingService
        assertThat(req.getPassengers().get(0).getSectors()).singleElement()
                .satisfies(sec -> {
                    assertThat(sec.getSectorFrom()).isEqualTo("AAA");
                    assertThat(sec.getCabinClass()).isEqualTo("Economy");
                });
    }

    @Test
    void aRoundTripServiceWithOneSectorHasNoReturnDate() {
        LeadService s = LeadService.builder().id("S1").type(ServiceType.FLIGHT).flightTripType(FlightTripType.ROUND_TRIP)
                .flightSectors(List.of(new FlightSectorDto("AAA", "BBB", LocalDate.of(2026, 2, 1)))).build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(), s, List.of(PAX), null);

        assertThat(req.getTripType()).isEqualTo("Round Trip");
        assertThat(req.getReturnDate()).isNull();
        assertThat(req.getFlightCabin()).isNull();
        assertThat(req.getPassengers().get(0).getSectors()).singleElement()
                .satisfies(sec -> assertThat(sec.getCabinClass()).isNull());
    }

    @Test
    void aFlightServiceWithoutSectorsFallsBackToTheLeadDestination() {
        LeadService noSectors = LeadService.builder().id("S1").type(ServiceType.FLIGHT).flightSectors(null).leadDestination("Lead Place").build();
        LeadService emptySectors = noSectors.toBuilder().flightSectors(List.of()).leadDestination(" ").label("Svc Label").build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(), noSectors, List.of(PAX), null);

        assertThat(req.getDestination()).isEqualTo("Lead Place");
        assertThat(req.getTripType()).isNull();
        assertThat(req.getFlightFrom()).isNull();
        assertThat(req.getPassengers().get(0).getSectors()).isNull();
        assertThat(BookingLogRequestMapper.toCreateRequest(log(), emptySectors, List.of(), null).getDestination())
                .isEqualTo("Svc Label");
    }

    @Test
    void aServiceWithNoDestinationAnywhereIsNamedByItsType() {
        LeadService visa = LeadService.builder().id("S1").type(ServiceType.VISA).visaCountry(" ").build();

        assertThat(BookingLogRequestMapper.toCreateRequest(log(), visa, List.of(), null).getDestination()).isEqualTo("VISA");
    }

    @Test
    void aVisaServiceFillsTheVisaFieldsAndTheApplicationNumber() {
        LeadService visa = LeadService.builder().id("S1").type(ServiceType.VISA).visaCountry("Japan")
                .visaAppointmentDate(LocalDate.of(2026, 3, 1)).visaIntendedTravelDate(LocalDate.of(2026, 4, 1)).build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(), visa, List.of(PAX), null);

        assertThat(req.getType()).isEqualTo(BookingType.VISA);
        assertThat(req.getDestination()).isEqualTo("Japan");
        assertThat(req.getVisaCountry()).isEqualTo("Japan");
        assertThat(req.getVisaAppointmentDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(req.getJourneyDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(req.getVisaApplicationNo()).isEqualTo("REF1");
        assertThat(req.getPassengers()).singleElement().satisfies(p -> assertThat(p.getSectors()).isNull());
    }

    @Test
    void aTransferServiceFillsTheTransferFieldsAndHasNoPassengers() {
        LeadService transfer = LeadService.builder().id("S1").type(ServiceType.TRANSFER).transferVehicleType("Sedan")
                .transferPickup("Airport").transferDropoff("Hotel").transferDate(LocalDate.of(2026, 5, 1))
                .transferTime("10:30").build();

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(), transfer, List.of(PAX), null);

        assertThat(req.getType()).isEqualTo(BookingType.TRANSFER);
        assertThat(req.getDestination()).isEqualTo("Hotel");
        assertThat(req.getTransferVehicleType()).isEqualTo("Sedan");
        assertThat(req.getTransferPickup()).isEqualTo("Airport");
        assertThat(req.getTransferDropoff()).isEqualTo("Hotel");
        assertThat(req.getTransferDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(req.getTransferTime()).isEqualTo("10:30");
        assertThat(req.getJourneyDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(req.getTransferVoucherNo()).isEqualTo("REF1");
        assertThat(req.getPassengers()).isNull();
    }

    @Test
    void anUnnamedTravellerIsCalledTravellerAndNoPriceSplitsZero() {
        LeadMember unnamed = LeadMember.builder().id("M9").fareClass(PaxType.CHILD).build();

        List<BookingPassengerRequest> pax = BookingLogRequestMapper.passengers(List.of(unnamed), null, null, null);

        assertThat(pax).singleElement().satisfies(p -> {
            assertThat(p.getPassengerName()).isEqualTo("Traveller");
            assertThat(p.getPaxType()).isEqualTo(PaxType.CHILD);
            assertThat(p.getFareAmount()).isEqualByComparingTo("0");
        });
    }
}
