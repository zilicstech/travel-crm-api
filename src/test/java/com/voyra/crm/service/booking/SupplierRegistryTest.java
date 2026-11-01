package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierRegistryTest {

    private final PlatformSupplierCredentialService suppliers = mock(PlatformSupplierCredentialService.class);
    private final FlightSearchProvider mockProvider = new MockFlightSearchProvider();
    private final HotelSearchProvider mockHotel = new MockHotelSearchProvider();
    private final FlightBookingProviderConfig flightConfig = new FlightBookingProviderConfig();
    private final HotelBookingProviderConfig hotelConfig = new HotelBookingProviderConfig();
    private final MockFlightBookingProvider mockFlightBooking = new MockFlightBookingProvider(flightConfig);
    private final MockHotelBookingProvider mockHotelBooking = new MockHotelBookingProvider(hotelConfig);
    private final SupplierRegistry registry = new SupplierRegistry(suppliers, List.of(mockProvider), List.of(mockHotel),
            List.of(mockFlightBooking), List.of(mockHotelBooking), flightConfig, hotelConfig);

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

    private static PlatformBooking booking(String credentialId, SupplierEnvironment environment, PricingProduct product) {
        return PlatformBooking.builder().id("B1").credentialId(credentialId).environment(environment).product(product).build();
    }

    @Test
    void servesNeedsTheRowItsIntegrationAndAMatchingEnvironment() {
        ResolvedCredential mock = row("s1", "Mock supplier", SupplierProvider.MOCK);
        ResolvedCredential tripjack = row("s2", "Tripjack", SupplierProvider.TRIPJACK);
        when(suppliers.find("s1")).thenReturn(Optional.of(mock));
        when(suppliers.find("s2")).thenReturn(Optional.of(tripjack));
        when(suppliers.find("gone")).thenReturn(Optional.empty());

        assertThat(registry.serves(booking(null, SupplierEnvironment.MOCK, PricingProduct.FLIGHT))).isFalse();
        assertThat(registry.serves(booking("gone", SupplierEnvironment.MOCK, PricingProduct.FLIGHT))).isFalse();
        assertThat(registry.serves(booking("s1", SupplierEnvironment.PROD, PricingProduct.FLIGHT))).isFalse();
        // a Tripjack row whose integration is not in this build (the registry above holds the mocks only)
        assertThat(registry.serves(booking("s2", SupplierEnvironment.UAT, PricingProduct.HOTEL))).isFalse();
        assertThat(registry.serves(booking("s1", SupplierEnvironment.MOCK, PricingProduct.FLIGHT))).isTrue();
        assertThat(registry.serves(booking("s1", SupplierEnvironment.MOCK, PricingProduct.HOTEL))).isTrue();
    }

    @Test
    void bookableNeedsBookingOnForThatProduct() {
        when(suppliers.find("s1")).thenReturn(Optional.of(row("s1", "Mock supplier", SupplierProvider.MOCK)));
        when(suppliers.find("s2")).thenReturn(Optional.of(row("s2", "Tripjack", SupplierProvider.TRIPJACK)));
        when(suppliers.bookingEnabledIds(PricingProduct.FLIGHT)).thenReturn(Set.of("s1", "s2"));
        when(suppliers.bookingEnabledIds(PricingProduct.HOTEL)).thenReturn(Set.of());

        assertThat(registry.bookable("s1", PricingProduct.FLIGHT)).isTrue();
        assertThat(registry.bookable("s1", PricingProduct.HOTEL)).isFalse();
        assertThat(registry.bookable("s2", PricingProduct.FLIGHT)).isFalse(); // booking on, but no Tripjack booking bean here
    }

    @Test
    void supplierForBindsTheRowsProviderAndRow() {
        ResolvedCredential mock = row("s1", "Mock supplier", SupplierProvider.MOCK);
        when(suppliers.find("s1")).thenReturn(Optional.of(mock));
        when(suppliers.find("gone")).thenReturn(Optional.empty());

        SupplierRegistry.BoundSupplier flight = registry.supplierFor(booking("s1", SupplierEnvironment.MOCK, PricingProduct.FLIGHT));
        SupplierRegistry.BoundSupplier hotel = registry.supplierFor(booking("s1", SupplierEnvironment.MOCK, PricingProduct.HOTEL));

        assertThat(flight.provider()).isSameAs(mockFlightBooking);
        assertThat(flight.credential()).isSameAs(mock);
        assertThat(hotel.provider()).isSameAs(mockHotelBooking);
        assertThat(flight.cancelIsAsync()).isFalse();
        assertThatThrownBy(() -> registry.supplierFor(booking("gone", SupplierEnvironment.MOCK, PricingProduct.FLIGHT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Booking B1 is not served by a supplier in this build");
    }

    @Test
    void aBoundSupplierCallsItsProviderWithItsRow() {
        BookingSupplier provider = mock(BookingSupplier.class);
        ResolvedCredential row = row("s1", "Mock supplier", SupplierProvider.MOCK);
        SupplierRegistry.BoundSupplier bound = new SupplierRegistry.BoundSupplier(provider, row);
        com.voyra.crm.models.booking.BookRequest request = new com.voyra.crm.models.booking.BookRequest("rv", java.math.BigDecimal.ONE,
                List.of(), null, null);

        bound.book(request);
        bound.bookingDetails("SB");
        bound.cancelQuote("SB");
        bound.cancel("SB");

        verify(provider).book(request, row);
        verify(provider).bookingDetails("SB", row);
        verify(provider).cancelQuote("SB", row);
        verify(provider).cancel("SB", row);
    }

    @Test
    void bookingIntegrationsArePickedByProviderAndPollWindowsByProduct() {
        flightConfig.getPoll().setMaxMinutes(30);
        hotelConfig.getPoll().setMaxMinutes(45);

        assertThat(registry.flightBooking(SupplierProvider.MOCK)).isSameAs(mockFlightBooking);
        assertThat(registry.hotelBooking(SupplierProvider.MOCK)).isSameAs(mockHotelBooking);
        assertThatThrownBy(() -> registry.flightBooking(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class).hasMessage("No flight booking integration for TRIPJACK");
        assertThatThrownBy(() -> registry.hotelBooking(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class).hasMessage("No hotel booking integration for TRIPJACK");
        assertThat(registry.pollMaxMinutes(PricingProduct.FLIGHT)).isEqualTo(30);
        assertThat(registry.pollMaxMinutes(PricingProduct.HOTEL)).isEqualTo(45);
    }
}
