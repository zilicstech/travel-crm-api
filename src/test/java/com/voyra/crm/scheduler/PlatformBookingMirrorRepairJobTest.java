package com.voyra.crm.scheduler;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.booking.PlatformBookingMirrorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformBookingMirrorRepairJobTest {

    @Mock private PlatformBookingRepository repository;
    @Mock private PlatformBookingMirrorService mirrorService;
    @Mock private com.voyra.crm.repository.PlatformBookingEventRepository eventRepository;
    @Mock private com.voyra.crm.service.EntitlementService entitlementService;
    @InjectMocks private PlatformBookingMirrorRepairJob job;

    private void plan(String tenantId, boolean crmCore) {
        when(entitlementService.resolve(tenantId)).thenReturn(new com.voyra.crm.models.Entitlements(com.voyra.crm.enums.SubscriptionPlan.SUITE,
                com.voyra.crm.enums.SubscriptionStatus.ACTIVE, crmCore ? EnumSet.allOf(com.voyra.crm.enums.Sku.class) : java.util.Set.of(),
                5, false, null));
    }

    /** An agency that gained CRM Core gets its earlier bookings backfilled; a Basic agency's stay as they are; one bad row never stops it. */
    @Test
    void backfillsOnlyAgenciesWithCrmCore() {
        when(repository.findTenantsWithUnmirroredBookings(PlatformBookingMirrorService.MIRRORED)).thenReturn(List.of("BASIC", "LITE"));
        plan("BASIC", false);
        plan("LITE", true);
        when(repository.findUnmirroredBookings("LITE", PlatformBookingMirrorService.MIRRORED))
                .thenReturn(List.of(PlatformBooking.builder().id("PB1").build(), PlatformBooking.builder().id("PB2").build()));
        doThrow(new IllegalStateException("unexpected")).when(mirrorService).backfill("PB1");

        job.run();

        verify(mirrorService).backfill("PB1");
        verify(mirrorService).backfill("PB2");
        verify(repository, org.mockito.Mockito.never()).findUnmirroredBookings(org.mockito.ArgumentMatchers.eq("BASIC"), any());
    }

    @Test
    void repairJobRetriesFailedMirrors() {
        when(repository.findByMirrorStatusIn(EnumSet.of(MirrorStatus.FAILED, MirrorStatus.PENDING))).thenReturn(List.of(
                PlatformBooking.builder().id("PB1").mirrorStatus(MirrorStatus.FAILED).build(),
                PlatformBooking.builder().id("PB2").mirrorStatus(MirrorStatus.PENDING).build()));
        doThrow(new IllegalStateException("unexpected")).when(mirrorService).sync("PB1");

        job.run(); // one bad row never stops the sweep

        verify(mirrorService).sync("PB1");
        verify(mirrorService).sync("PB2");
    }

    /** After 144 failed attempts (about a day, already alerted) a row stays FAILED for an admin instead of retrying forever. */
    @Test
    void aMirrorThatFailed144TimesIsNoLongerRetried() {
        when(repository.findByMirrorStatusIn(EnumSet.of(MirrorStatus.FAILED, MirrorStatus.PENDING))).thenReturn(List.of(
                PlatformBooking.builder().id("PB1").mirrorStatus(MirrorStatus.FAILED).build(),
                PlatformBooking.builder().id("PB2").mirrorStatus(MirrorStatus.FAILED).build()));
        when(eventRepository.countByPlatformBookingIdAndType("PB1", "MIRROR_FAILED")).thenReturn(144L);
        when(eventRepository.countByPlatformBookingIdAndType("PB2", "MIRROR_FAILED")).thenReturn(143L);

        job.run();

        verify(mirrorService, org.mockito.Mockito.never()).sync("PB1");
        verify(mirrorService).sync("PB2");
    }

    /** A day of failures at BOOKING never stops the ticket phase: the cap counts only failures since the newest MIRROR_QUEUED. */
    @Test
    void failuresBeforeTheLastQueueDoNotCountTowardsTheCap() {
        Instant queuedAt = Instant.parse("2026-11-01T10:00:00Z");
        when(repository.findByMirrorStatusIn(EnumSet.of(MirrorStatus.FAILED, MirrorStatus.PENDING))).thenReturn(List.of(
                PlatformBooking.builder().id("PB1").mirrorStatus(MirrorStatus.PENDING).build(),
                PlatformBooking.builder().id("PB2").mirrorStatus(MirrorStatus.FAILED).build()));
        when(eventRepository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc("PB1", "MIRROR_QUEUED"))
                .thenReturn(Optional.of(com.voyra.crm.entity.PlatformBookingEvent.builder().createdAt(queuedAt).build()));
        when(eventRepository.countByPlatformBookingIdAndTypeAndCreatedAtAfter("PB1", "MIRROR_FAILED", queuedAt)).thenReturn(3L);
        when(eventRepository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc("PB2", "MIRROR_QUEUED"))
                .thenReturn(Optional.of(com.voyra.crm.entity.PlatformBookingEvent.builder().createdAt(queuedAt).build()));
        when(eventRepository.countByPlatformBookingIdAndTypeAndCreatedAtAfter("PB2", "MIRROR_FAILED", queuedAt)).thenReturn(144L);

        job.run();

        verify(mirrorService).sync("PB1");
        verify(mirrorService, org.mockito.Mockito.never()).sync("PB2");
    }
}
