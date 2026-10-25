package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PricingProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Routes every supplier call of a platform booking to its product's provider: one state machine, one provider per product. */
@Component
@RequiredArgsConstructor
public class BookingProviderRegistry {

    private final FlightBookingProvider flights;
    private final HotelBookingProvider hotels;
    private final FlightBookingProviderConfig flightConfig;
    private final HotelBookingProviderConfig hotelConfig;

    public BookingSupplier forProduct(PricingProduct product) {
        return switch (product) {
            case FLIGHT -> flights;
            case HOTEL -> hotels;
        };
    }

    public BookingSupplier supplierFor(PlatformBooking booking) {
        return forProduct(booking.getProduct());
    }

    public FlightBookingProvider flights() {
        return flights;
    }

    public HotelBookingProvider hotels() {
        return hotels;
    }

    /** A mock supplier needs no platform credential and books in the MOCK environment. */
    public boolean isMock(PricingProduct product) {
        String provider = switch (product) {
            case FLIGHT -> flightConfig.getProvider();
            case HOTEL -> hotelConfig.getProvider();
        };
        return "mock".equalsIgnoreCase(provider);
    }
}
