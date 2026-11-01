package com.voyra.crm.service.booking;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.PlatformSupplierCredentialService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * The supplier rows the booking engine tests run on: {@code s1} the mock supplier, {@code tj-uat} and {@code tj-prod}
 * Tripjack accounts. All take bookings for both products until a test switches one off.
 */
final class BookingSuppliers {

    /** When every row was last edited (name, URL or key): before any review a test makes. */
    static final Instant EDITED = Instant.parse("2026-01-01T00:00:00Z");
    static final ResolvedCredential S1 = new ResolvedCredential("s1", "Mock supplier", SupplierProvider.MOCK,
            SupplierEnvironment.MOCK, "mock://local", "", null, EDITED);
    static final ResolvedCredential TJ_UAT = new ResolvedCredential("tj-uat", "Tripjack", SupplierProvider.TRIPJACK,
            SupplierEnvironment.UAT, "https://apitest.tripjack.com", "uat-key", null, EDITED);
    static final ResolvedCredential TJ_PROD = new ResolvedCredential("tj-prod", "Tripjack live", SupplierProvider.TRIPJACK,
            SupplierEnvironment.PROD, "https://api.tripjack.com", "live-key", null, EDITED);

    private BookingSuppliers() {
    }

    /**
     * A registry over the rows above (plus {@code more}) whose MOCK booking beans are {@code mock} and {@code hotel} and whose
     * TRIPJACK flight bean is {@code tripjack} (an asynchronous cancel, as Tripjack's is).
     */
    static SupplierRegistry registry(PlatformSupplierCredentialService suppliers, FlightBookingProvider mock,
                                     FlightBookingProvider tripjack, HotelBookingProvider hotel, FlightBookingProviderConfig flightConfig,
                                     HotelBookingProviderConfig hotelConfig, ResolvedCredential... more) {
        lenient().when(mock.provider()).thenReturn(SupplierProvider.MOCK);
        lenient().when(hotel.provider()).thenReturn(SupplierProvider.MOCK);
        lenient().when(tripjack.provider()).thenReturn(SupplierProvider.TRIPJACK);
        lenient().when(tripjack.cancelIsAsync()).thenReturn(true);
        List<ResolvedCredential> rows = new java.util.ArrayList<>(List.of(S1, TJ_UAT, TJ_PROD));
        rows.addAll(List.of(more));
        for (ResolvedCredential row : rows) {
            lenient().when(suppliers.find(row.id())).thenReturn(Optional.of(row));
        }
        lenient().when(suppliers.bookingEnabledIds(any())).thenReturn(Set.copyOf(rows.stream().map(ResolvedCredential::id).toList()));
        return new SupplierRegistry(suppliers, List.of(), List.of(), List.of(mock, tripjack), List.of(hotel),
                flightConfig, hotelConfig);
    }
}
