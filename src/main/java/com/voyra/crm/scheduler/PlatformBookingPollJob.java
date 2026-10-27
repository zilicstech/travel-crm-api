package com.voyra.crm.scheduler;

import com.voyra.crm.service.booking.PlatformBookingPoller;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: ask the supplier about BOOKING / UNKNOWN platform bookings. Public schema only. */
@Component
@RequiredArgsConstructor
public class PlatformBookingPollJob {

    private final PlatformBookingPoller poller;

    @Scheduled(cron = "${app.flight-booking.poll.cron:0 * * * * *}")
    public void run() {
        poller.poll();
    }
}
