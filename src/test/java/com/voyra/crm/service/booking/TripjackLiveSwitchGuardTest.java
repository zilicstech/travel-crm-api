package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Review Focus 3: the app refuses to start on a Tripjack booking provider until tripjack_live_ready is on. */
class TripjackLiveSwitchGuardTest {

    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final PlatformBookingRepository bookings = mock(PlatformBookingRepository.class);
    private final FlightBookingProviderConfig flights = new FlightBookingProviderConfig();
    private final HotelBookingProviderConfig hotels = new HotelBookingProviderConfig();

    private void check() {
        new TripjackLiveSwitchGuard(flights, hotels, settings, bookings).check();
    }

    @Test
    void mockProvidersStartWithoutReadingTheSetting() {
        assertThatCode(this::check).doesNotThrowAnyException();
        verifyNoInteractions(settings);
    }

    @Test
    void aTripjackProviderFailsFastUntilLiveReady() {
        hotels.setProvider("tripjack");
        assertThatThrownBy(this::check).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOTEL_BOOKING_PROVIDER=tripjack").hasMessageContaining("tripjack_live_ready");

        hotels.setProvider("mock");
        flights.setProvider("TRIPJACK");
        assertThatThrownBy(this::check).isInstanceOf(IllegalStateException.class).hasMessageContaining("FLIGHT_BOOKING_PROVIDER=tripjack");

        when(settings.tripjackLiveReady()).thenReturn(true);
        assertThatCode(this::check).doesNotThrowAnyException();
    }

    /**
     * Final review Important 1: with the switch off, Tripjack bookings still in flight need the Tripjack provider to settle
     * (the mock would answer for them). The instance starts, with a WARN; new live bookings stay blocked at review and confirm.
     */
    @Test
    void aTripjackProviderStartsWithTheSwitchOffWhileTripjackBookingsAreInFlight() {
        flights.setProvider("tripjack");
        when(bookings.countByStatusInAndEnvironmentNot(EnumSet.of(PlatformBookingStatus.HELD, PlatformBookingStatus.BOOKING,
                PlatformBookingStatus.UNKNOWN, PlatformBookingStatus.CANCELLING), SupplierEnvironment.MOCK)).thenReturn(2L);
        assertThatCode(this::check).doesNotThrowAnyException();

        when(bookings.countByStatusInAndEnvironmentNot(any(), any())).thenReturn(0L);
        assertThatThrownBy(this::check).isInstanceOf(IllegalStateException.class).hasMessageContaining("unset FLIGHT_BOOKING_PROVIDER");
    }
}
