package com.voyra.crm.service.booking;

import com.voyra.crm.service.PlatformSettingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a Tripjack booking provider until platform setting {@code tripjack_live_ready} is on (set only by a
 * second platform admin). Runs while the context starts, before the scheduler polls, so a Tripjack book is never sent
 * from an instance that was not cleared for it.
 */
@Component
@RequiredArgsConstructor
public class TripjackLiveSwitchGuard {

    private final FlightBookingProviderConfig flights;
    private final HotelBookingProviderConfig hotels;
    private final PlatformSettingService settings;

    @PostConstruct
    void check() {
        String env = "tripjack".equalsIgnoreCase(flights.getProvider()) ? "FLIGHT_BOOKING_PROVIDER"
                : "tripjack".equalsIgnoreCase(hotels.getProvider()) ? "HOTEL_BOOKING_PROVIDER" : null;
        if (env != null && !settings.tripjackLiveReady()) {
            throw new IllegalStateException(env + "=tripjack is refused: platform setting tripjack_live_ready is false. "
                    + "A platform admin requests it and a second admin approves it (Platform settings), then restart - "
                    + "or unset " + env + " to keep the mock supplier.");
        }
    }
}
