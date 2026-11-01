package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;
import com.voyra.crm.service.FlightSearchProvider;
import com.voyra.crm.service.HotelSearchProvider;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which supplier rows to ask, and which integration in this build talks to each. Keys are decrypted per call, so a key
 * change applies to the next call. A booking is served by the row it was reviewed on, for as long as that row's
 * integration is in the build and its environment is the booking's; the booking switch gates review and confirm only.
 */
@Service
@Slf4j
public class SupplierRegistry {

    private final PlatformSupplierCredentialService suppliers;
    private final Map<SupplierProvider, FlightSearchProvider> flightSearch = new EnumMap<>(SupplierProvider.class);
    private final Map<SupplierProvider, HotelSearchProvider> hotelSearch = new EnumMap<>(SupplierProvider.class);
    private final Map<SupplierProvider, FlightBookingProvider> flightBooking = new EnumMap<>(SupplierProvider.class);
    private final Map<SupplierProvider, HotelBookingProvider> hotelBooking = new EnumMap<>(SupplierProvider.class);
    private final FlightBookingProviderConfig flightConfig;
    private final HotelBookingProviderConfig hotelConfig;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public SupplierRegistry(PlatformSupplierCredentialService suppliers, List<FlightSearchProvider> flightSearch,
                            List<HotelSearchProvider> hotelSearch, List<FlightBookingProvider> flightBooking,
                            List<HotelBookingProvider> hotelBooking, FlightBookingProviderConfig flightConfig,
                            HotelBookingProviderConfig hotelConfig) {
        this.suppliers = suppliers;
        flightSearch.forEach(p -> this.flightSearch.put(p.provider(), p));
        hotelSearch.forEach(p -> this.hotelSearch.put(p.provider(), p));
        flightBooking.forEach(p -> this.flightBooking.put(p.provider(), p));
        hotelBooking.forEach(p -> this.hotelBooking.put(p.provider(), p));
        this.flightConfig = flightConfig;
        this.hotelConfig = hotelConfig;
    }

    /** A booking provider bound to the supplier row a booking runs on. */
    public record BoundSupplier(BookingSupplier provider, ResolvedCredential credential) {

        public BookResult book(BookRequest request) {
            return provider.book(request, credential);
        }

        public BookResult bookingDetails(String supplierBookingId) {
            return provider.bookingDetails(supplierBookingId, credential);
        }

        public CancelQuote cancelQuote(String supplierBookingId) {
            return provider.cancelQuote(supplierBookingId, credential);
        }

        public CancelResult cancel(String supplierBookingId) {
            return provider.cancel(supplierBookingId, credential);
        }

        public boolean cancelIsAsync() {
            return provider.cancelIsAsync();
        }
    }

    /** Suppliers with search on for the product whose integration is in this build (others are skipped, warned once per row). */
    public List<ResolvedCredential> searchable(PricingProduct product) {
        return suppliers.searchEnabled(product).stream().filter(s -> {
            boolean present = (product == PricingProduct.FLIGHT ? flightSearch.containsKey(s.provider()) : hotelSearch.containsKey(s.provider()));
            if (!present && warned.add(s.id())) {
                log.warn("Supplier {} ({}) is on for {} search but this build has no {} integration; skipped",
                        s.name(), s.provider(), product, s.provider());
            }
            return present;
        }).toList();
    }

    public FlightSearchProvider flightSearch(SupplierProvider provider) {
        FlightSearchProvider found = flightSearch.get(provider);
        if (found == null) {
            throw new IllegalStateException("No flight search integration for " + provider);
        }
        return found;
    }

    public HotelSearchProvider hotelSearch(SupplierProvider provider) {
        HotelSearchProvider found = hotelSearch.get(provider);
        if (found == null) {
            throw new IllegalStateException("No hotel search integration for " + provider);
        }
        return found;
    }

    public FlightBookingProvider flightBooking(SupplierProvider provider) {
        FlightBookingProvider found = flightBooking.get(provider);
        if (found == null) {
            throw new IllegalStateException("No flight booking integration for " + provider);
        }
        return found;
    }

    public HotelBookingProvider hotelBooking(SupplierProvider provider) {
        HotelBookingProvider found = hotelBooking.get(provider);
        if (found == null) {
            throw new IllegalStateException("No hotel booking integration for " + provider);
        }
        return found;
    }

    /** Booking switched on for the product, and this build has the row's booking integration. */
    public boolean bookable(String supplierId, PricingProduct product) {
        return suppliers.bookingEnabledIds(product).contains(supplierId)
                && find(supplierId).filter(s -> booking(product, s.provider()) != null).isPresent();
    }

    public boolean serves(PlatformBooking b) {
        return bound(b).isPresent();
    }

    /** @throws IllegalStateException when the booking is not {@link #serves served} */
    public BoundSupplier supplierFor(PlatformBooking b) {
        return bound(b).orElseThrow(() -> new IllegalStateException("Booking " + b.getId() + " is not served by a supplier in this build"));
    }

    /** Minutes the poller waits for a booking's final answer before it goes UNKNOWN, per product. */
    public int pollMaxMinutes(PricingProduct product) {
        return switch (product) {
            case FLIGHT -> flightConfig.getPoll().getMaxMinutes();
            case HOTEL -> hotelConfig.getPoll().getMaxMinutes();
        };
    }

    private Optional<BoundSupplier> bound(PlatformBooking b) {
        if (b.getCredentialId() == null) {
            return Optional.empty();
        }
        return find(b.getCredentialId()).filter(s -> s.environment() == b.getEnvironment())
                .flatMap(s -> Optional.ofNullable(booking(b.getProduct(), s.provider())).map(p -> new BoundSupplier(p, s)));
    }

    private BookingSupplier booking(PricingProduct product, SupplierProvider provider) {
        return product == PricingProduct.FLIGHT ? flightBooking.get(provider) : hotelBooking.get(provider);
    }

    public Optional<ResolvedCredential> find(String supplierId) {
        return suppliers.find(supplierId);
    }

    public Set<String> bookingEnabledIds(PricingProduct product) {
        return suppliers.bookingEnabledIds(product);
    }

    /** Names, never keys, so "why does search show nothing" is answerable from the startup log. */
    @EventListener(ApplicationReadyEvent.class)
    void logSuppliers() {
        try {
            for (PricingProduct product : PricingProduct.values()) {
                suppliers.searchEnabled(product).forEach(s -> log.info("Supplier {} ({}, {}) has {} search on",
                        s.name(), s.provider(), s.environment(), product));
            }
        } catch (RuntimeException e) { // a log line must never stop the app starting
            log.warn("Could not list the enabled suppliers at startup: {}", e.getClass().getSimpleName());
        }
    }
}
