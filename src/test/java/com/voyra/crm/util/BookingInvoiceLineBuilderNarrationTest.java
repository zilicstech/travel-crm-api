package com.voyra.crm.util;

import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.enums.InvoiceServiceCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Each category's line narration, in the source system's phrasing. */
class BookingInvoiceLineBuilderNarrationTest {

    private static final LocalDate D1 = LocalDate.of(2026, 12, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 12, 4);

    private static Booking booking() {
        return Booking.builder().id("B1").clientName("Client Name").sellingPrice(new BigDecimal("500")).build();
    }

    private static String narration(Booking b, InvoiceServiceCategory category) {
        return BookingInvoiceLineBuilder.build(b, category, List.of(), Map.of()).get(0).getDescription();
    }

    private static BookingPassenger pax(String id) {
        return BookingPassenger.builder().id(id).passengerName("Pax " + id).fareAmount(new BigDecimal("100")).taxAmount(null).build();
    }

    @Test
    void aPassengerWithoutATaxAmountIsChargedTheFareAndEachGetsTheirOwnSectors() {
        Booking b = booking().toBuilder().pnr("PNR9").build();
        Map<String, List<BookingSector>> sectors = Map.of("P1", List.of(
                BookingSector.builder().sectorFrom("AAA").sectorTo("BBB").travelDate(D1).flightNumber("XX 1").build(),
                BookingSector.builder().sectorFrom(null).sectorTo("CCC").build()));

        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(b, InvoiceServiceCategory.AIR_DOMESTIC,
                List.of(pax("P1"), pax("P2")), sectors);

        assertThat(lines).extracting(InvoiceLineItemRequest::getDescription).containsExactly(
                "Pax: Pax P1 X 1. 01/12/26 AAA/BBB XX 1; /CCC Pnr: PNR9",
                "Pax: Pax P2 X 1. / Pnr: PNR9");
        assertThat(lines.get(0).getUnitPrice()).isEqualByComparingTo("100");
        assertThat(lines.get(0).getTaxAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aFlightWithoutSectorsUsesTheBookingsOwnLeg() {
        Booking full = booking().toBuilder().journeyDate(D1).flightFrom("AAA").flightTo("BBB").flightNumber("XX 2").pnr("P").build();

        assertThat(narration(full, InvoiceServiceCategory.RAIL)).isEqualTo("Pax: Client Name X 1. 01/12/26 AAA/BBB XX 2 Pnr: P");
        assertThat(narration(booking(), InvoiceServiceCategory.AIR_INTERNATIONAL)).isEqualTo("Pax: Client Name X 1. /");
    }

    @Test
    void sectorsWithoutAPnrEndAtTheLastLeg() {
        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(booking(), InvoiceServiceCategory.AIR_DOMESTIC,
                List.of(pax("P1")), Map.of("P1", List.of(BookingSector.builder().sectorFrom("AAA").sectorTo("BBB").build())));

        assertThat(lines.get(0).getDescription()).isEqualTo("Pax: Pax P1 X 1. AAA/BBB");
    }

    @Test
    void aHotelLineNamesTheStay() {
        Booking full = booking().toBuilder().hotelName("Hotel X").hotelRoomType("Deluxe").hotelBoardBasis("Breakfast")
                .hotelCheckIn(D1).hotelCheckOut(D2).build();

        assertThat(narration(full, InvoiceServiceCategory.HOTEL))
                .isEqualTo("Guest: Client Name. Hotel X Deluxe (Breakfast) Check in: 01/12/26 To: 04/12/26");
        assertThat(narration(booking(), InvoiceServiceCategory.HOTEL)).isEqualTo("Guest: Client Name.   Check in:  To: ");
    }

    @Test
    void aVisaLineNamesTheCountryAndDates() {
        Booking full = booking().toBuilder().visaCountry("Japan").visaAppliedDate(D1).visaAppointmentDate(D2).build();

        assertThat(narration(full, InvoiceServiceCategory.VISA)).isEqualTo("Applicant: Client Name. Japan visa, applied 01/12/26, appt 04/12/26");
        assertThat(narration(booking(), InvoiceServiceCategory.VISA)).isEqualTo("Applicant: Client Name.  visa");
    }

    @Test
    void aTransportLineNamesTheRide() {
        Booking full = booking().toBuilder().transferVehicleType("Sedan").transferPickup("Airport").transferDropoff("Hotel")
                .transferDate(D1).transferTime("10:30").build();

        assertThat(narration(full, InvoiceServiceCategory.TRANSPORT)).isEqualTo("Sedan Airport to Hotel on 01/12/26 10:30");
        assertThat(narration(booking(), InvoiceServiceCategory.TRANSPORT)).isEqualTo("  to ");
    }

    @Test
    void aPackageOrMiscLineNamesTheDestinationAndNotes() {
        assertThat(narration(booking().toBuilder().destination("Goa").notes("Beach").build(), InvoiceServiceCategory.PACKAGE))
                .isEqualTo("Goa - Beach");
        assertThat(narration(booking().toBuilder().destination("Goa").notes(" ").build(), InvoiceServiceCategory.MISCELLANEOUS))
                .isEqualTo("Goa");
        assertThat(narration(booking(), InvoiceServiceCategory.MISCELLANEOUS)).isEmpty();
    }

    @Test
    void aCategoryThatIsNotPassengerWiseIgnoresCapturedPassengers() {
        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(booking().toBuilder().destination("Goa").build(),
                InvoiceServiceCategory.MISCELLANEOUS, List.of(pax("P1")), Map.of());

        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.getDescription()).isEqualTo("Goa");
            assertThat(l.getUnitPrice()).isEqualByComparingTo("500");
        });
    }
}
