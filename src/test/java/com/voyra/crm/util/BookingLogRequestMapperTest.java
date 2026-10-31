package com.voyra.crm.util;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingLogRequestMapperTest {

    private static BookingLogRequest log(String reference, String sellingPrice) {
        BookingLogRequest r = new BookingLogRequest();
        r.setReference(reference);
        r.setNetCost(new BigDecimal("900"));
        r.setSellingPrice(new BigDecimal(sellingPrice));
        // Request values the service must override.
        r.setClientId("WRONG-CLIENT");
        r.setType(BookingType.PACKAGE);
        r.setDestination("Wrong place");
        return r;
    }

    private static LeadMember member(String id, String name, LeadMemberStatus status) {
        return LeadMember.builder().id(id).memberName(name).status(status).build();
    }

    private static LeadService roundTripFlight() {
        return LeadService.builder().id("S1").leadId("L1").clientId("C1").type(ServiceType.FLIGHT)
                .assignedAgentId("AG1").leadDestination("Delhi trip")
                .flightTripType(FlightTripType.ROUND_TRIP).flightCabin(FlightCabin.PREMIUM_ECONOMY)
                .flightSectors(List.of(
                        new FlightSectorDto("BOM", "DEL", LocalDate.of(2026, 11, 12)),
                        new FlightSectorDto("DEL", "BOM", LocalDate.of(2026, 11, 18))))
                .build();
    }

    @Test
    void serviceBookingIsFilledFromTheServiceNotTheRequest() {
        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(log(" PNR123 ", "1000"), roundTripFlight(),
                List.of(member("M1", "Asha", LeadMemberStatus.CONFIRMED)), "IndiGo Desk");

        assertThat(req.getClientId()).isEqualTo("C1");
        assertThat(req.getLeadId()).isEqualTo("L1");
        assertThat(req.getServiceId()).isEqualTo("S1");
        assertThat(req.getAgentId()).as("the booking's agent is the caller, set by BookingService").isNull();
        assertThat(req.getType()).isEqualTo(BookingType.FLIGHT);
        assertThat(req.getDestination()).isEqualTo("DEL");
        assertThat(req.getFlightFrom()).isEqualTo("BOM");
        assertThat(req.getJourneyDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(req.getReturnDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(req.getTripType()).isEqualTo("Round Trip");
        assertThat(req.getFlightCabin()).isEqualTo("Premium Economy");
        assertThat(req.getPnr()).isEqualTo("PNR123");
        assertThat(req.getSupplier()).isEqualTo("IndiGo Desk");
        assertThat(req.getPassengers()).hasSize(1);
        assertThat(req.getPassengers().get(0).getSectors()).hasSize(2);
    }

    @Test
    void sellingPriceIsSplitAcrossTravellersAndDroppedOnesAreLeftOut() {
        List<LeadMember> members = List.of(
                member("M1", "Asha", LeadMemberStatus.CONFIRMED),
                member("M2", "Ravi", LeadMemberStatus.TENTATIVE),
                member("M3", "Dropped Dev", LeadMemberStatus.DROPPED),
                member("M4", "Kiran", LeadMemberStatus.CONFIRMED));

        List<BookingPassengerRequest> pax = BookingLogRequestMapper.toCreateRequest(
                log("PNR", "1000"), roundTripFlight(), members, null).getPassengers();

        assertThat(pax).extracting(BookingPassengerRequest::getLeadMemberId).containsExactly("M1", "M2", "M4");
        assertThat(pax).extracting(BookingPassengerRequest::getFareAmount).containsExactly(
                new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
        assertThat(pax.stream().map(BookingPassengerRequest::getFareAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");
    }

    @Test
    void untickedTravellersAreLeftOut() {
        BookingLogRequest r = log("PNR", "500");
        r.setLeadMemberIds(List.of("M2"));
        List<BookingPassengerRequest> pax = BookingLogRequestMapper.toCreateRequest(r, roundTripFlight(),
                List.of(member("M1", "Asha", LeadMemberStatus.CONFIRMED), member("M2", "Ravi", LeadMemberStatus.CONFIRMED)),
                null).getPassengers();

        assertThat(pax).extracting(BookingPassengerRequest::getPassengerName).containsExactly("Ravi");
        assertThat(pax.get(0).getFareAmount()).isEqualByComparingTo("500");
    }

    @Test
    void hotelReferenceGoesToConfirmationNumberAndARequestedAgentIsNotCopied() {
        LeadService hotel = LeadService.builder().id("S2").leadId("L1").clientId("C1").type(ServiceType.HOTEL)
                .hotelCity("Dubai").hotelCheckIn(LocalDate.of(2026, 11, 12)).hotelCheckOut(LocalDate.of(2026, 11, 15))
                .hotelRooms(2).build();
        BookingLogRequest r = log("HC-77", "800");
        r.setAgentId("AG9");

        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(r, hotel, List.of(), null);

        assertThat(req.getHotelConfirmationNo()).isEqualTo("HC-77");
        assertThat(req.getPnr()).isNull();
        assertThat(req.getDestination()).isEqualTo("Dubai");
        assertThat(req.getHotelRooms()).isEqualTo(2);
        assertThat(req.getJourneyDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(req.getReturnDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(req.getAgentId()).isNull();
        assertThat(req.getPassengers()).isNull();
    }

    @Test
    void adHocBookingNeedsClientTypeAndDestination() {
        BookingLogRequest r = new BookingLogRequest();
        r.setReference("REF");
        r.setNetCost(BigDecimal.ONE);
        r.setSellingPrice(BigDecimal.TEN);
        assertThatThrownBy(() -> BookingLogRequestMapper.toCreateRequest(r, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Client is required");

        r.setClientId("C1");
        r.setType(BookingType.PACKAGE);
        r.setDestination("  Goa  ");
        r.setJourneyDate(LocalDate.of(2026, 12, 1));
        BookingCreateRequest req = BookingLogRequestMapper.toCreateRequest(r, null, List.of(), null);
        assertThat(req.getDestination()).isEqualTo("Goa");
        assertThat(req.getPnr()).isEqualTo("REF");
        assertThat(req.getServiceId()).isNull();
        assertThat(req.getPassengers()).isNull();
    }
}
