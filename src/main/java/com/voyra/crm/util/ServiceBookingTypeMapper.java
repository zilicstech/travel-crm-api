package com.voyra.crm.util;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ServiceType;

/**
 * Bridges BookingType and ServiceType, which stay two separate enums on purpose: one is what
 * an agent is licensed for ({@code agent.manageable_services}), the other is what a booking
 * records. {@code PACKAGE} has no service counterpart (standalone bookings only) and every
 * ServiceType has exactly one BookingType counterpart. When a booking is logged on a service,
 * its {@code type} must equal {@link #forService(ServiceType)} - enforced where the booking is
 * created - so the Bookings-tab type filter and the agent-scoping predicate never disagree
 * about what a service-linked booking's type means.
 */
public final class ServiceBookingTypeMapper {

    private ServiceBookingTypeMapper() {
    }

    public static BookingType forService(ServiceType serviceType) {
        return switch (serviceType) {
            case FLIGHT -> BookingType.FLIGHT;
            case HOTEL -> BookingType.HOTEL;
            case VISA -> BookingType.VISA;
            case TRANSFER -> BookingType.TRANSFER;
        };
    }
}
