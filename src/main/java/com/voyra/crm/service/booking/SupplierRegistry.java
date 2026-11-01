package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
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

/** Which supplier rows to ask, and which integration in this build talks to each. Keys are decrypted per call. */
@Service
@Slf4j
public class SupplierRegistry {

    private final PlatformSupplierCredentialService suppliers;
    private final Map<SupplierProvider, FlightSearchProvider> flightSearch = new EnumMap<>(SupplierProvider.class);
    private final Map<SupplierProvider, HotelSearchProvider> hotelSearch = new EnumMap<>(SupplierProvider.class);
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public SupplierRegistry(PlatformSupplierCredentialService suppliers, List<FlightSearchProvider> flightSearch,
                            List<HotelSearchProvider> hotelSearch) {
        this.suppliers = suppliers;
        flightSearch.forEach(p -> this.flightSearch.put(p.provider(), p));
        hotelSearch.forEach(p -> this.hotelSearch.put(p.provider(), p));
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
