package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.FlightSearchProvider;
import com.voyra.crm.service.HotelSearchProvider;
import com.voyra.crm.service.MockFlightSearchProvider;
import com.voyra.crm.service.MockHotelSearchProvider;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupplierRegistryTest {

    private final PlatformSupplierCredentialService suppliers = mock(PlatformSupplierCredentialService.class);
    private final FlightSearchProvider mockProvider = new MockFlightSearchProvider();
    private final HotelSearchProvider mockHotel = new MockHotelSearchProvider();
    private final SupplierRegistry registry = new SupplierRegistry(suppliers, List.of(mockProvider), List.of(mockHotel));

    private static ResolvedCredential row(String id, String name, SupplierProvider provider) {
        return new ResolvedCredential(id, name, provider, provider == SupplierProvider.MOCK ? SupplierEnvironment.MOCK : SupplierEnvironment.UAT,
                "x", "secret-key", null, null);
    }

    @Test
    void searchableSkipsARowWhoseIntegrationIsNotInTheBuildAndWarnsOnce() {
        ResolvedCredential mock = row("s1", "Mock supplier", SupplierProvider.MOCK);
        when(suppliers.searchEnabled(PricingProduct.FLIGHT)).thenReturn(List.of(mock, row("s2", "Tripjack", SupplierProvider.TRIPJACK)));

        try (LogCapture log = new LogCapture(SupplierRegistry.class)) {
            assertThat(registry.searchable(PricingProduct.FLIGHT)).containsExactly(mock);
            assertThat(registry.searchable(PricingProduct.FLIGHT)).containsExactly(mock);

            assertThat(log.lines()).containsExactly("WARN Supplier Tripjack (TRIPJACK) is on for FLIGHT search but this build has no TRIPJACK integration; skipped");
        }
    }

    @Test
    void hotelRowsAreFilteredByTheHotelIntegrationsAndWarnOnce() {
        ResolvedCredential mock = row("s1", "Mock supplier", SupplierProvider.MOCK);
        when(suppliers.searchEnabled(PricingProduct.HOTEL)).thenReturn(List.of(mock, row("s2", "Tripjack", SupplierProvider.TRIPJACK)));

        try (LogCapture log = new LogCapture(SupplierRegistry.class)) {
            assertThat(registry.searchable(PricingProduct.HOTEL)).containsExactly(mock);
            assertThat(registry.searchable(PricingProduct.HOTEL)).containsExactly(mock);

            assertThat(log.lines()).containsExactly("WARN Supplier Tripjack (TRIPJACK) is on for HOTEL search but this build has no TRIPJACK integration; skipped");
        }
    }

    @Test
    void hotelSearchFindsTheIntegrationOrFails() {
        assertThat(registry.hotelSearch(SupplierProvider.MOCK)).isSameAs(mockHotel);
        assertThatThrownBy(() -> registry.hotelSearch(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class).hasMessage("No hotel search integration for TRIPJACK");
    }

    @Test
    void flightSearchFindsTheIntegrationOrFails() {
        assertThat(registry.flightSearch(SupplierProvider.MOCK)).isSameAs(mockProvider);
        assertThatThrownBy(() -> registry.flightSearch(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class).hasMessage("No flight search integration for TRIPJACK");
    }

    @Test
    void findAndBookingEnabledIdsDelegate() {
        ResolvedCredential mock = row("s1", "Mock supplier", SupplierProvider.MOCK);
        when(suppliers.find("s1")).thenReturn(Optional.of(mock));
        when(suppliers.bookingEnabledIds(PricingProduct.FLIGHT)).thenReturn(Set.of("s1"));

        assertThat(registry.find("s1")).contains(mock);
        assertThat(registry.bookingEnabledIds(PricingProduct.FLIGHT)).containsExactly("s1");
    }

    @Test
    void startupLoggingSurvivesAnUnreadableSupplier() {
        when(suppliers.searchEnabled(PricingProduct.FLIGHT)).thenThrow(new IllegalStateException("secret-key-123"));

        try (LogCapture log = new LogCapture(SupplierRegistry.class)) {
            registry.logSuppliers();

            assertThat(log.lines()).containsExactly("WARN Could not list the enabled suppliers at startup: IllegalStateException");
        }
    }

    @Test
    void startupLogsEachEnabledRowByNameAndNeverTheKey() {
        when(suppliers.searchEnabled(PricingProduct.FLIGHT)).thenReturn(List.of(row("s1", "Mock supplier", SupplierProvider.MOCK)));
        when(suppliers.searchEnabled(PricingProduct.HOTEL)).thenReturn(List.of());

        try (LogCapture log = new LogCapture(SupplierRegistry.class)) {
            registry.logSuppliers();

            assertThat(log.lines()).containsExactly("INFO Supplier Mock supplier (MOCK, MOCK) has FLIGHT search on");
        }
    }
}
