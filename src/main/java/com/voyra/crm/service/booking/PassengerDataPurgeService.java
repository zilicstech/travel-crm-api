package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.NotificationOutboxRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * DPDP data protection run (daily): passenger data of finished platform bookings older than the retention period
 * ({@code pax_retention_days}), plus email bodies still held on old notification_outbox rows. Not transactional itself:
 * every booking is purged in its own transaction by {@link PlatformBookingPurgeTxService}. Logs and the audit entry carry counts only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassengerDataPurgeService {

    static final Duration OUTBOX_BODY_RETENTION = Duration.ofDays(7);

    private final PlatformSettingService settings;
    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingPurgeTxService purgeTx;
    private final NotificationOutboxRepository outboxRepository;
    private final PlatformAuditService auditService;

    public void run() {
        Instant now = Instant.now();
        int retentionDays = settings.paxRetentionDays();
        List<String> candidates = bookingRepository.findPurgeCandidateIds(
                PlatformBookingPurgeTxService.TERMINAL, now.minus(Duration.ofDays(retentionDays)));
        int purged = 0;
        int failed = 0;
        for (String id : candidates) {
            try {
                if (purgeTx.purge(id, now, retentionDays)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Passenger data purge failed for platform booking {}: {}", id, e.getClass().getSimpleName());
            }
        }
        int bodies = outboxRepository.clearOldBodies(now.minus(OUTBOX_BODY_RETENTION));
        auditService.recordSystem(PlatformAuditAction.PASSENGER_DATA_PURGE, "PASSENGER_PURGE", LocalDate.now(ZoneOffset.UTC).toString(),
                Map.of("retentionDays", retentionDays, "examined", candidates.size(), "bookingsPurged", purged,
                        "bookingsFailed", failed, "outboxBodiesCleared", bodies));
        log.info("Passenger data purge done: {} booking(s) purged of {} examined, {} failed, {} outbox body(ies) cleared",
                purged, candidates.size(), failed, bodies);
    }
}
