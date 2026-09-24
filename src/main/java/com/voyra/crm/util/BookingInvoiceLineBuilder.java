package com.voyra.crm.util;

import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.enums.InvoiceServiceCategory;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a booking (plus its {@link BookingPassenger}/{@link BookingSector} rows) into the line
 * list an invoice draft is built from, so {@code InvoiceDocumentService} never asks an operator
 * to type a description, quantity or unit price - see {@code ACCOUNTING_REDESIGN_SPEC.md} §5.1
 * and §5.4. Every {@link InvoiceLineItemRequest} this returns still goes through
 * {@code TaxEngine} exactly as a hand-typed one would; only where the numbers and text come
 * from changes.
 *
 * <p>A {@linkplain InvoiceServiceCategory#isPassengerWise() passenger-wise} category (air,
 * hotel, rail, visa, package) prints one line per traveller, their fare stated once regardless
 * of how many itinerary legs follow - matching the source system in
 * {@code ACCOUNTING_REDESIGN_SPEC.md} §2.4. A booking with no captured passengers yet (every
 * booking made before this feature existed) falls back to one line for the whole booking, so an
 * old booking is still invoiceable - see gap 1.
 */
public final class BookingInvoiceLineBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy");

    private BookingInvoiceLineBuilder() {
    }

    public static List<InvoiceLineItemRequest> build(Booking booking, InvoiceServiceCategory category,
                                                       List<BookingPassenger> passengers,
                                                       Map<String, List<BookingSector>> sectorsByPassengerId) {
        List<InvoiceLineItemRequest> lines = new ArrayList<>();
        if (category.isPassengerWise() && !passengers.isEmpty()) {
            for (BookingPassenger p : passengers) {
                lines.add(lineFor(booking, category, p, sectorsByPassengerId.getOrDefault(p.getId(), List.of())));
            }
            return lines;
        }
        // Not passenger-wise, or passenger-wise but none captured yet: one line for the booking.
        lines.add(wholeBookingLine(booking, category));
        return lines;
    }

    private static InvoiceLineItemRequest lineFor(Booking booking, InvoiceServiceCategory category,
                                                   BookingPassenger passenger, List<BookingSector> sectors) {
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription(narrationFor(booking, category, passenger, sectors));
        line.setServiceType(booking.getServiceType());
        line.setQuantity(java.math.BigDecimal.ONE);
        line.setUnitPrice(passenger.getFareAmount());
        return line;
    }

    private static InvoiceLineItemRequest wholeBookingLine(Booking booking, InvoiceServiceCategory category) {
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription(narrationFor(booking, category, null, List.of()));
        line.setServiceType(booking.getServiceType());
        line.setQuantity(java.math.BigDecimal.ONE);
        line.setUnitPrice(booking.getSellingPrice());
        return line;
    }

    /** Per-category narration, in the source system's own phrasing - see spec §5.4/§2.6. */
    private static String narrationFor(Booking booking, InvoiceServiceCategory category,
                                        BookingPassenger passenger, List<BookingSector> sectors) {
        String name = passenger != null ? passenger.getPassengerName() : booking.getClientName();
        return switch (category) {
            case AIR_INTERNATIONAL, AIR_DOMESTIC, RAIL -> "Pax: " + name + " X 1. " + legsText(sectors, booking);
            case HOTEL -> "Guest: " + name + ". " + nvl(booking.getHotelName()) + " " + nvl(booking.getHotelRoomType())
                    + (booking.getHotelBoardBasis() != null ? " (" + booking.getHotelBoardBasis() + ")" : "")
                    + " Check in: " + fmt(booking.getHotelCheckIn()) + " To: " + fmt(booking.getHotelCheckOut());
            case VISA -> "Applicant: " + name + ". " + nvl(booking.getVisaCountry()) + " visa"
                    + (booking.getVisaAppliedDate() != null ? ", applied " + fmt(booking.getVisaAppliedDate()) : "")
                    + (booking.getVisaAppointmentDate() != null ? ", appt " + fmt(booking.getVisaAppointmentDate()) : "");
            case TRANSPORT -> nvl(booking.getTransferVehicleType()) + " " + nvl(booking.getTransferPickup())
                    + " to " + nvl(booking.getTransferDropoff())
                    + (booking.getTransferDate() != null ? " on " + fmt(booking.getTransferDate()) : "")
                    + (booking.getTransferTime() != null ? " " + booking.getTransferTime() : "");
            case PACKAGE, MISCELLANEOUS -> nvl(booking.getDestination())
                    + (booking.getNotes() != null && !booking.getNotes().isBlank() ? " - " + booking.getNotes() : "");
        };
    }

    private static String legsText(List<BookingSector> sectors, Booking booking) {
        if (sectors.isEmpty()) {
            // No sector rows captured yet - fall back to the booking's own single-leg fields.
            return (booking.getJourneyDate() != null ? fmt(booking.getJourneyDate()) + " " : "")
                    + nvl(booking.getFlightFrom()) + "/" + nvl(booking.getFlightTo())
                    + (booking.getFlightNumber() != null ? " " + booking.getFlightNumber() : "")
                    + (booking.getPnr() != null ? " Pnr: " + booking.getPnr() : "");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sectors.size(); i++) {
            BookingSector s = sectors.get(i);
            if (i > 0) sb.append("; ");
            if (s.getTravelDate() != null) sb.append(fmt(s.getTravelDate())).append(' ');
            sb.append(nvl(s.getSectorFrom())).append('/').append(nvl(s.getSectorTo()));
            if (s.getFlightNumber() != null) sb.append(' ').append(s.getFlightNumber());
        }
        if (booking.getPnr() != null) sb.append(" Pnr: ").append(booking.getPnr());
        return sb.toString();
    }

    private static String fmt(java.time.LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
