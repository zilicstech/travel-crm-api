package com.voyra.crm.scheduler;

import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Tripjack's hotel city list and nationalities loaded, so no search ever waits on them: shortly after startup,
 * then hourly - a run only calls Tripjack when the copy is missing or a week old, so it reloads weekly and retries
 * within the hour after a failure. Uses the platform credential (public schema), so no tenant is involved.
 */
@Component
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "tripjack")
@RequiredArgsConstructor
@Slf4j
public class TripjackHotelStaticDataJob {

    private final TripjackHotelSearchProvider tripjackHotelSearchProvider;

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT1H")
    public void run() {
        try {
            tripjackHotelSearchProvider.refreshStaticData();
        } catch (IllegalStateException e) {
            log.info("Tripjack hotel static data not loaded: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Tripjack hotel static data refresh failed: {}", e.getMessage());
        }
    }
}
