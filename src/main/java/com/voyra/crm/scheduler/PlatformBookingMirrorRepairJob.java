package com.voyra.crm.scheduler;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.booking.PlatformBookingMirrorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Every 10 minutes: retries the CRM mirrors of platform bookings that failed or never ran (spec 6). Public schema only. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBookingMirrorRepairJob {

    private static final Set<MirrorStatus> TO_REPAIR = EnumSet.of(MirrorStatus.FAILED, MirrorStatus.PENDING);
    /** About 24 hours of 10-minute retries; the first failure already alerted Voyra, so the row then waits for an admin. */
    static final long MAX_ATTEMPTS = 144;

    private final PlatformBookingRepository repository;
    private final PlatformBookingMirrorService mirrorService;
    private final PlatformBookingEventRepository eventRepository;

    @Scheduled(cron = "${app.flight-booking.mirror-repair.cron:0 */10 * * * *}")
    public void run() {
        for (PlatformBooking b : repository.findByMirrorStatusIn(TO_REPAIR)) {
            if (eventRepository.countByPlatformBookingIdAndType(b.getId(), "MIRROR_FAILED") >= MAX_ATTEMPTS) {
                continue;
            }
            try {
                mirrorService.sync(b.getId());
            } catch (RuntimeException e) {
                log.error("Mirror repair of platform booking {} failed: {}", b.getId(), e.getMessage(), e);
            }
        }
    }
}
