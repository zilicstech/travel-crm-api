package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.PlatformSettingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fails startup on a Tripjack booking provider until platform setting {@code tripjack_live_ready} is on (set by a
 * platform admin, audited). Runs while the context starts, before the scheduler polls, so a Tripjack book is never sent
 * from an instance that was not cleared for it. Exception: with the switch off but Tripjack bookings still in flight
 * (HELD, BOOKING, UNKNOWN, CANCELLING), the instance starts with a WARN, because only the Tripjack provider can settle
 * them (the mock would answer for bookings it never saw); new live bookings stay refused at review and confirm.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripjackLiveSwitchGuard {

    static final Set<PlatformBookingStatus> IN_FLIGHT = EnumSet.of(PlatformBookingStatus.HELD, PlatformBookingStatus.BOOKING,
            PlatformBookingStatus.UNKNOWN, PlatformBookingStatus.CANCELLING);

    private final FlightBookingProviderConfig flights;
    private final HotelBookingProviderConfig hotels;
    private final PlatformSettingService settings;
    private final PlatformBookingRepository bookings;

    @PostConstruct
    void check() {
        String env = "tripjack".equalsIgnoreCase(flights.getProvider()) ? "FLIGHT_BOOKING_PROVIDER"
                : "tripjack".equalsIgnoreCase(hotels.getProvider()) ? "HOTEL_BOOKING_PROVIDER" : null;
        if (env == null || settings.tripjackLiveReady()) {
            return;
        }
        long inFlight = bookings.countByStatusInAndEnvironmentNot(IN_FLIGHT, SupplierEnvironment.MOCK);
        if (inFlight > 0) {
            log.warn("{}=tripjack with tripjack_live_ready false: starting anyway because {} Tripjack booking(s) are still in flight "
                    + "(HELD, BOOKING, UNKNOWN or CANCELLING) and only the Tripjack provider can settle them. New live bookings stay paused. "
                    + "Keep {}=tripjack until they have all settled.", env, inFlight, env);
            return;
        }
        throw new IllegalStateException(env + "=tripjack is refused: platform setting tripjack_live_ready is false. "
                + "A platform admin turns on Go live (Platform > Supplier), then restart - "
                + "or unset " + env + " to keep the mock supplier.");
    }
}
