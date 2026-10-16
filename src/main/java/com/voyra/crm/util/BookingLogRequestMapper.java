package com.voyra.crm.util;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.BookingSectorRequest;
import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadMemberStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the short "log a booking" form into the full {@link BookingCreateRequest} the existing
 * create path takes. For a service booking everything descriptive comes from the service, never
 * from the request, so the booking cannot disagree with what the lead says was sold.
 */
public final class BookingLogRequestMapper {

    private BookingLogRequestMapper() {
    }

    public static BookingCreateRequest toCreateRequest(BookingLogRequest log, LeadService service,
                                                       List<LeadMember> leadMembers, String vendorName) {
        BookingCreateRequest req = new BookingCreateRequest();
        req.setVendorId(blankToNull(log.getVendorId()));
        req.setSupplier(vendorName);
        req.setNetCost(log.getNetCost());
        req.setSellingPrice(log.getSellingPrice());
        req.setNotes(blankToNull(log.getNotes()));

        if (service == null) {
            if (blankToNull(log.getClientId()) == null) throw new IllegalArgumentException("Client is required");
            if (log.getType() == null) throw new IllegalArgumentException("Type is required");
            if (blankToNull(log.getDestination()) == null) throw new IllegalArgumentException("Destination is required");
            req.setClientId(log.getClientId());
            req.setAgentId(log.getAgentId());
            req.setType(log.getType());
            req.setDestination(log.getDestination().trim());
            req.setJourneyDate(log.getJourneyDate());
            req.setReturnDate(log.getReturnDate());
            if (log.getType() == BookingType.FLIGHT) {
                req.setInternationalTrip(log.getInternationalTrip());
                req.setTripType(log.getReturnDate() != null ? "Round Trip" : log.getJourneyDate() != null ? "One Way" : null);
            }
        } else {
            BookingType type = ServiceBookingTypeMapper.forService(service.getType());
            req.setClientId(service.getClientId());
            req.setLeadId(service.getLeadId());
            req.setServiceId(service.getId());
            req.setAgentId(service.getAssignedAgentId() != null ? service.getAssignedAgentId() : log.getAgentId());
            req.setType(type);
            req.setDestination(destinationFor(service));
            fillFromService(req, service, log);
            if (type != BookingType.TRANSFER) {
                req.setPassengers(passengers(leadMembers, log.getLeadMemberIds(), log.getSellingPrice(),
                        type == BookingType.FLIGHT ? sectors(service) : null));
            }
        }
        setReference(req, log.getReference().trim());
        return req;
    }

    public static BookingDocumentType voucherTypeFor(BookingType type) {
        return switch (type) {
            case FLIGHT -> BookingDocumentType.E_TICKET;
            case HOTEL -> BookingDocumentType.HOTEL_VOUCHER;
            case VISA -> BookingDocumentType.VISA_COPY;
            case TRANSFER -> BookingDocumentType.TRANSFER_VOUCHER;
            case PACKAGE -> BookingDocumentType.OTHER;
        };
    }

    private static void fillFromService(BookingCreateRequest req, LeadService s, BookingLogRequest log) {
        switch (s.getType()) {
            case FLIGHT -> {
                List<FlightSectorDto> legs = s.getFlightSectors() != null ? s.getFlightSectors() : List.of();
                if (!legs.isEmpty()) {
                    req.setFlightFrom(legs.get(0).getFrom());
                    req.setFlightTo(legs.get(0).getTo());
                    req.setJourneyDate(legs.get(0).getDate());
                }
                boolean roundTrip = s.getFlightTripType() == FlightTripType.ROUND_TRIP;
                if (roundTrip && legs.size() > 1) req.setReturnDate(legs.get(1).getDate());
                req.setTripType(s.getFlightTripType() == null ? null : roundTrip ? "Round Trip" : "One Way");
                req.setFlightCabin(s.getFlightCabin() != null ? titleCase(s.getFlightCabin().name()) : null);
                req.setInternationalTrip(log.getInternationalTrip());
            }
            case HOTEL -> {
                req.setHotelCity(s.getHotelCity());
                req.setHotelCountryCode(s.getHotelCountryCode());
                req.setHotelCheckIn(s.getHotelCheckIn());
                req.setHotelCheckOut(s.getHotelCheckOut());
                req.setHotelRooms(s.getHotelRooms());
                req.setJourneyDate(s.getHotelCheckIn());
                req.setReturnDate(s.getHotelCheckOut());
            }
            case VISA -> {
                req.setVisaCountry(s.getVisaCountry());
                req.setVisaAppointmentDate(s.getVisaAppointmentDate());
                req.setJourneyDate(s.getVisaIntendedTravelDate());
            }
            case TRANSFER -> {
                req.setTransferVehicleType(s.getTransferVehicleType());
                req.setTransferPickup(s.getTransferPickup());
                req.setTransferDropoff(s.getTransferDropoff());
                req.setTransferDate(s.getTransferDate());
                req.setTransferTime(s.getTransferTime());
                req.setJourneyDate(s.getTransferDate());
            }
        }
    }

    private static String destinationFor(LeadService s) {
        String fromService = switch (s.getType()) {
            case FLIGHT -> s.getFlightSectors() == null || s.getFlightSectors().isEmpty() ? null
                    : s.getFlightSectors().get(0).getTo();
            case HOTEL -> s.getHotelCity();
            case VISA -> s.getVisaCountry();
            case TRANSFER -> s.getTransferDropoff();
        };
        String d = blankToNull(fromService) != null ? fromService : blankToNull(s.getLeadDestination());
        return d != null ? d : (s.getLabel() != null ? s.getLabel() : s.getType().name());
    }

    /** One line per traveller, the selling price split evenly so the invoice's per-passenger
     *  lines still add up to it (rounding remainder lands on the last traveller). */
    static List<BookingPassengerRequest> passengers(List<LeadMember> members, List<String> pickedIds,
                                                    BigDecimal sellingPrice, List<BookingSectorRequest> sectors) {
        List<LeadMember> onBooking = members.stream()
                .filter(m -> m.getStatus() != LeadMemberStatus.DROPPED)
                .filter(m -> pickedIds == null || pickedIds.contains(m.getId()))
                .toList();
        if (onBooking.isEmpty()) return null;
        BigDecimal total = sellingPrice != null ? sellingPrice : BigDecimal.ZERO;
        BigDecimal share = total.divide(BigDecimal.valueOf(onBooking.size()), 2, RoundingMode.DOWN);
        List<BookingPassengerRequest> out = new ArrayList<>();
        for (int i = 0; i < onBooking.size(); i++) {
            LeadMember m = onBooking.get(i);
            BookingPassengerRequest p = new BookingPassengerRequest();
            p.setLeadMemberId(m.getId());
            p.setPassengerName(m.getMemberName() != null ? m.getMemberName() : "Traveller");
            p.setPaxType(m.getFareClass());
            boolean last = i == onBooking.size() - 1;
            p.setFareAmount(last ? total.subtract(share.multiply(BigDecimal.valueOf(i))) : share);
            p.setSectors(sectors);
            out.add(p);
        }
        return out;
    }

    private static List<BookingSectorRequest> sectors(LeadService s) {
        if (s.getFlightSectors() == null) return null;
        List<FlightSectorDto> legs = s.getFlightTripType() == FlightTripType.ROUND_TRIP
                ? s.getFlightSectors() : s.getFlightSectors().stream().limit(1).toList();
        String cabin = s.getFlightCabin() != null ? titleCase(s.getFlightCabin().name()) : null;
        return legs.stream().map(l -> {
            BookingSectorRequest r = new BookingSectorRequest();
            r.setSectorFrom(l.getFrom());
            r.setSectorTo(l.getTo());
            r.setTravelDate(l.getDate());
            r.setCabinClass(cabin);
            return r;
        }).toList();
    }

    private static void setReference(BookingCreateRequest req, String reference) {
        switch (req.getType()) {
            case FLIGHT, PACKAGE -> req.setPnr(reference);
            case HOTEL -> req.setHotelConfirmationNo(reference);
            case VISA -> req.setVisaApplicationNo(reference);
            case TRANSFER -> req.setTransferVoucherNo(reference);
        }
    }

    private static String titleCase(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
