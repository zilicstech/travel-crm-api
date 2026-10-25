package com.voyra.crm.service.booking;

import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Review Focus 3: the app refuses to start on a Tripjack booking provider until tripjack_live_ready is on. */
class TripjackLiveSwitchGuardTest {

    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final FlightBookingProviderConfig flights = new FlightBookingProviderConfig();
    private final HotelBookingProviderConfig hotels = new HotelBookingProviderConfig();

    private void check() {
        new TripjackLiveSwitchGuard(flights, hotels, settings).check();
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
}
