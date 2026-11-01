package com.voyra.crm.scheduler;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.EntitlementService;
import com.voyra.crm.service.booking.PlatformBookingMirrorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every 10 minutes: retries the CRM mirrors of platform bookings that failed or never ran (spec 6), and backfills the
 * CRM bookings of an agency that has gained CRM Core since it booked. Public schema only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBookingMirrorRepairJob {

    private static final Set<MirrorStatus> TO_REPAIR = EnumSet.of(MirrorStatus.FAILED, MirrorStatus.PENDING);
    /** About 24 hours of 10-minute retries; the first failure already alerted Voyra, so the row then waits for an admin. */
    public static final long MAX_ATTEMPTS = 144;

    private final PlatformBookingRepository repository;
    private final PlatformBookingMirrorService mirrorService;
    private final PlatformBookingEventRepository eventRepository;
    private final EntitlementService entitlementService;

    @Scheduled(cron = "${app.flight-booking.mirror-repair.cron:0 */10 * * * *}")
    public void run() {
        for (PlatformBooking b : repository.findByMirrorStatusIn(TO_REPAIR)) {
            if (attempts(eventRepository, b.getId()) >= MAX_ATTEMPTS) {
                continue;
            }
            try {
                mirrorService.sync(b.getId());
            } catch (RuntimeException e) {
                log.error("Mirror repair of platform booking {} failed: {}", b.getId(), e.getMessage(), e);
            }
        }
        // shortcut: an upgraded agency sees its earlier bookings within 10 minutes; hook the plan change if that is too slow
        for (String tenantId : repository.findTenantsWithUnmirroredBookings(PlatformBookingMirrorService.MIRRORED)) {
            if (!entitlementService.resolve(tenantId).skus().contains(Sku.CRM_CORE)) {
                continue;
            }
            for (PlatformBooking b : repository.findUnmirroredBookings(tenantId, PlatformBookingMirrorService.MIRRORED)) {
                try {
                    mirrorService.backfill(b.getId());
                } catch (RuntimeException e) {
                    log.error("Mirror backfill of platform booking {} failed: {}", b.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * MIRROR_FAILED events since the newest MIRROR_QUEUED (each status move), so the cap is per status, not per row; all
     * of them when the row was never queued. The DPDP purge uses the same count to decide a mirror was abandoned.
     */
    public static long attempts(PlatformBookingEventRepository events, String id) {
        return events.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(id, "MIRROR_QUEUED")
                .map(q -> events.countByPlatformBookingIdAndTypeAndCreatedAtAfter(id, "MIRROR_FAILED", q.getCreatedAt()))
                .orElseGet(() -> events.countByPlatformBookingIdAndType(id, "MIRROR_FAILED"));
    }
}
