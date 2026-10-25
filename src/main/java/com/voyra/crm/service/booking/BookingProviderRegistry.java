package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
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

    /**
     * Whether the provider configured now is the one this row was booked with: the mock serves MOCK rows only, Tripjack
     * every other row. A mismatched row (provider switched with bookings in flight) must not be asked or settled.
     */
    public boolean serves(PlatformBooking booking) {
        return isMock(booking.getProduct()) == (booking.getEnvironment() == SupplierEnvironment.MOCK);
    }

    /** Minutes the poller waits for a booking's final answer before it goes UNKNOWN, per product. */
    public int pollMaxMinutes(PricingProduct product) {
        return switch (product) {
            case FLIGHT -> flightConfig.getPoll().getMaxMinutes();
            case HOTEL -> hotelConfig.getPoll().getMaxMinutes();
        };
    }
}
