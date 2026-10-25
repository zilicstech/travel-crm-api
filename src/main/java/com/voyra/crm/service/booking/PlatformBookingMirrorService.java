package com.voyra.crm.service.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.security.SystemActor;
import com.voyra.crm.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the agency's read-only CRM mirror of a platform booking in line with it (spec 6 "Tenant side"): written once the
 * booking is TICKETED, cancelled once it is CANCELLED - only for a booking that names a CRM client. Runs after the
 * platform money committed and never touches it: a failure sets {@code mirror_status = FAILED}, records an event and
 * alerts Voyra, and {@code PlatformBookingMirrorRepairJob} retries. The tenant writes run as the {@link SystemActor} in
 * {@link PlatformBookingMirrorTenantService}'s own {@code REQUIRES_NEW} transactions (blueprint 3.5).
 */
@Slf4j
@Service
public class PlatformBookingMirrorService {

    private final PlatformBookingRepository repository;
    private final PlatformBookingMirrorTenantService tenantService;
    private final PlatformBookingQueryService queryService;
    private final PaxCodec paxCodec;
    private final PlatformBookingEventRecorder events;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public PlatformBookingMirrorService(PlatformBookingRepository repository, PlatformBookingMirrorTenantService tenantService,
                                        PlatformBookingQueryService queryService, PaxCodec paxCodec,
                                        PlatformBookingEventRecorder events, NotificationService notificationService,
                                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tenantService = tenantService;
        this.queryService = queryService;
        this.paxCodec = paxCodec;
        this.events = events;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The passengers as stored at confirm; contact details are not mirrored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredPax(List<PaxInput> passengers) {
    }

    /** Brings the mirror in line with the platform row. Idempotent, and never throws: the ticket is already paid for. */
    public void sync(String platformBookingId) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "The mirror must run after the platform booking's transaction committed, not inside it");
        PlatformBooking b = repository.findById(platformBookingId).orElse(null);
        if (b == null || b.getClientId() == null || b.getMirrorStatus() == MirrorStatus.DONE
                || (b.getStatus() != PlatformBookingStatus.TICKETED && b.getStatus() != PlatformBookingStatus.CANCELLED)) {
            return; // no CRM client (a Starter agency's record is the platform row), already in line, or still moving
        }
        try {
            List<ItinerarySegment> legs = queryService.itinerary(b);
            List<PaxInput> pax = b.getPaxEnc() == null ? List.of() : paxCodec.decrypt(b.getPaxEnc(), StoredPax.class).passengers();
            String bookingId = SystemActor.call(b.getTenantId(), () -> {
                String id = tenantService.create(b, legs, pax == null ? List.of() : pax);
                if (b.getStatus() == PlatformBookingStatus.CANCELLED) {
                    tenantService.cancel(id, b);
                }
                return id;
            });
            finish(platformBookingId, bookingId, null);
        } catch (RuntimeException e) {
            log.warn("CRM mirror of platform booking {} failed: {}", platformBookingId, e.toString());
            finish(platformBookingId, null, e);
        }
    }

    /** Records the outcome under the row lock; a concurrent sync that already finished the mirror is never marked FAILED. */
    private void finish(String id, String tenantBookingId, RuntimeException error) {
        boolean alert = Boolean.TRUE.equals(tx.execute(s -> {
            PlatformBooking b = repository.findByIdForUpdate(id).orElse(null);
            if (b == null || (error != null && b.getMirrorStatus() == MirrorStatus.DONE)) {
                return false;
            }
            MirrorStatus before = b.getMirrorStatus();
            Map<String, Object> detail = new LinkedHashMap<>();
            if (error == null) {
                b.setMirrorStatus(MirrorStatus.DONE);
                b.setTenantBookingId(tenantBookingId);
                detail.put("tenantBookingId", tenantBookingId);
                detail.put("platformStatus", b.getStatus().name());
                events.record(id, b.getStatus() == PlatformBookingStatus.CANCELLED ? "MIRROR_CANCELLED" : "MIRRORED", detail);
            } else {
                b.setMirrorStatus(MirrorStatus.FAILED);
                detail.put("error", truncate(error.getClass().getSimpleName() + ": " + error.getMessage()));
                events.record(id, "MIRROR_FAILED", detail);
            }
            repository.save(b);
            return error != null && before != MirrorStatus.FAILED; // a failing retry alerts once, not every 10 minutes
        }));
        if (alert) {
            notificationService.platformAlert("CRM mirror of booking " + id + " failed",
                    "The agency's CRM copy of platform booking " + id + " could not be written (" + error.getClass().getSimpleName()
                            + ": " + truncate(error.getMessage()) + "). The ticket and its wallet debit are unaffected; "
                            + "the repair job retries every 10 minutes.");
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
