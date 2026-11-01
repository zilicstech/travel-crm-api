package com.voyra.crm.service.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.voyra.crm.dto.HotelStaySummary;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.security.SystemActor;
import com.voyra.crm.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the agency's read-only CRM mirror of a platform booking in line with it (unified bookings spec Part 2): PENDING
 * from the moment the booking is sent, CONFIRMED and paid once ticketed, cancelled when it fails or is cancelled - only
 * for a booking that names a CRM client. Runs after the platform money committed and never touches it: a failure sets {@code mirror_status = FAILED}, records an event and
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

    /** The platform statuses the CRM shows; REVIEWING, HELD and ABORTED never have a CRM row, CANCELLING changes nothing. */
    private static final Set<PlatformBookingStatus> MIRRORED = EnumSet.of(PlatformBookingStatus.BOOKING,
            PlatformBookingStatus.UNKNOWN, PlatformBookingStatus.TICKETED, PlatformBookingStatus.FAILED, PlatformBookingStatus.CANCELLED);

    /**
     * Brings the mirror in line with the platform row by running the tenant phases in order, each a no-op when the CRM
     * row already shows it: ensure (always), ticket (once ticketed, whatever the status now), fail, cancel. Idempotent,
     * and never throws: the platform money already committed.
     */
    public void sync(String platformBookingId) {
        PlatformBooking b = repository.findById(platformBookingId).orElse(null);
        if (b == null || b.getClientId() == null || b.getMirrorStatus() == MirrorStatus.DONE || !MIRRORED.contains(b.getStatus())) {
            return; // no CRM client (a Starter agency's record is the platform row), already in line, or nothing to show
        }
        PlatformBookingStatus acted = b.getStatus();
        try {
            Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                    "The mirror must run after the platform booking's transaction committed, not inside it");
            boolean hotel = b.getProduct() == PricingProduct.HOTEL;
            HotelStaySummary stay = hotel ? queryService.hotel(b) : null;
            List<ItinerarySegment> legs = hotel ? List.of() : queryService.itinerary(b);
            List<PaxInput> stored = b.getPaxEnc() == null ? List.of() : paxCodec.decrypt(b.getPaxEnc(), StoredPax.class).passengers();
            List<PaxInput> pax = stored == null ? List.of() : stored;
            String bookingId = SystemActor.call(b.getTenantId(), () -> {
                String id = tenantService.ensure(b, legs, stay, pax);
                if (b.getTicketedAt() != null) {
                    tenantService.ticket(id, b, legs, pax);
                }
                if (acted == PlatformBookingStatus.FAILED) {
                    tenantService.fail(id);
                }
                if (acted == PlatformBookingStatus.CANCELLED) {
                    tenantService.cancel(id, b);
                }
                return id;
            });
            finish(platformBookingId, acted, bookingId, null);
        } catch (RuntimeException e) {
            log.warn("CRM mirror of platform booking {} failed: {}", platformBookingId, e.toString());
            finish(platformBookingId, acted, null, e);
        }
    }

    /**
     * Prices the CRM booking of a ticketed platform booking at the published fare plus {@code markup}, as the system in
     * the agency's schema on behalf of {@code by} (who CRM History names), with no platform transaction open. Unlike {@link #sync} its refusals reach the caller.
     */
    public void setMarkup(PlatformBooking pb, BigDecimal markup, SystemActor.Author by) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "setMarkup must not run inside a platform transaction");
        SystemActor.call(pb.getTenantId(), by, () -> {
            tenantService.setMarkup(pb, markup);
            return null;
        });
    }

    /** The CRM client, lead and service a review names exist in the caller's tenant and the caller may reach them. */
    public void requireLinks(String clientId, String leadId, String serviceId) {
        tenantService.requireLinks(clientId, leadId, serviceId);
    }

    /**
     * Records the outcome under the row lock; a concurrent sync that already finished the mirror is never marked FAILED.
     * DONE only when the row is still at the status this sync acted on: a cancel that landed meanwhile leaves the mirror
     * PENDING, so the next sync (the cancel's own, or the repair job) cancels the CRM booking.
     */
    private void finish(String id, PlatformBookingStatus acted, String tenantBookingId, RuntimeException error) {
        boolean alert = Boolean.TRUE.equals(tx.execute(s -> {
            PlatformBooking b = repository.findByIdForUpdate(id).orElse(null);
            if (b == null || (error != null && b.getMirrorStatus() == MirrorStatus.DONE)) {
                return false;
            }
            MirrorStatus before = b.getMirrorStatus();
            Map<String, Object> detail = new LinkedHashMap<>();
            if (error == null && b.getStatus() != acted) {
                if (b.getTenantBookingId() == null) {
                    b.setTenantBookingId(tenantBookingId);
                    repository.save(b);
                }
                return false;
            }
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
