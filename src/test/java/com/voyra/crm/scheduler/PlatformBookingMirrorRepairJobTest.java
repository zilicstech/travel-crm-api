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
}
