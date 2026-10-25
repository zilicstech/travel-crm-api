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

import java.util.EnumSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformBookingMirrorRepairJobTest {

    @Mock private PlatformBookingRepository repository;
    @Mock private PlatformBookingMirrorService mirrorService;
    @Mock private com.voyra.crm.repository.PlatformBookingEventRepository eventRepository;
    @InjectMocks private PlatformBookingMirrorRepairJob job;

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
}
