package com.voyra.crm.util;

import com.voyra.crm.entity.LeadService;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Derives one service's {@code date_from}/{@code date_to} from its type-specific fields, per
 * docs/LLD_LEAD_MANAGEMENT.md §4.5. Recomputed in the service layer on every write to that
 * service, before the row is saved - never left for the client to compute, since the lead-level
 * travel window is a roll-up across every non-cancelled service's dates.
 */
public final class ServiceDateRangeDeriver {

    private ServiceDateRangeDeriver() {
    }

    public static void derive(LeadService service) {
        switch (service.getType()) {
            case FLIGHT -> deriveFromSectors(service);
            case HOTEL -> deriveFromHotel(service);
            case VISA -> {
                service.setDateFrom(service.getVisaIntendedTravelDate());
                service.setDateTo(service.getVisaIntendedTravelDate());
            }
            case TRANSFER -> {
                service.setDateFrom(service.getTransferDate());
                service.setDateTo(service.getTransferDate());
            }
        }
    }

    private static void deriveFromSectors(LeadService service) {
        List<LocalDate> dates = service.getFlightSectors() == null ? List.of()
                : service.getFlightSectors().stream()
                        .map(com.voyra.crm.dto.FlightSectorDto::getDate)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        service.setDateFrom(dates.stream().min(Comparator.naturalOrder()).orElse(null));
        service.setDateTo(dates.stream().max(Comparator.naturalOrder()).orElse(null));
    }

    private static void deriveFromHotel(LeadService service) {
        LocalDate from = service.getHotelCheckIn();
        LocalDate to = service.getHotelCheckOut();
        if (to == null && from != null && service.getHotelNights() != null) {
            to = from.plusDays(service.getHotelNights());
        }
        if (to == null) {
            to = from;
        }
        service.setDateFrom(from);
        service.setDateTo(to);
    }
}
