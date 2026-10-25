package com.voyra.crm.scheduler;

import com.voyra.crm.service.booking.PassengerDataPurgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily DPDP purge of finished platform bookings' passenger data. Public schema only.
 * {@code app.passenger-purge.enabled} must be false on all but one instance when scaled out.
 */
@Component
@ConditionalOnProperty(name = "app.passenger-purge.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class PassengerDataPurgeJob {

    private final PassengerDataPurgeService purgeService;

    @Scheduled(cron = "${app.passenger-purge.cron:0 45 3 * * *}")
    public void run() {
        purgeService.run();
    }
}
