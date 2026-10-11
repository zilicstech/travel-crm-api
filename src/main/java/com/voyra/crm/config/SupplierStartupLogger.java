package com.voyra.crm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which supplier search providers this instance actually started with, so "why is prod
 * still returning mock offers" is answerable from the startup log alone. Logs configuration
 * values only - never a credential.
 */
@Component
@Slf4j
public class SupplierStartupLogger {

    @Value("${app.flight-supplier.provider:}")
    private String flightProvider;

    @Value("${app.hotel-supplier.provider:}")
    private String hotelProvider;

    @EventListener(ApplicationReadyEvent.class)
    public void logSupplierConfig() {
        log.info("Supplier config at startup: flightProvider={}, hotelProvider={}, "
                + "FLIGHT_SUPPLIER env present={}", flightProvider, hotelProvider,
                System.getenv("FLIGHT_SUPPLIER") != null);
        if ("mock".equalsIgnoreCase(flightProvider)) {
            log.warn("Flight search is using the MOCK provider - offers are generated, not from Tripjack. "
                    + "Set FLIGHT_SUPPLIER=tripjack on this service to use the real supplier.");
        }
    }
}
