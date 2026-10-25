package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.NotificationOutboxRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PassengerDataPurgeServiceTest {

    @Mock private PlatformSettingService settings;
    @Mock private PlatformBookingRepository bookings;
    @Mock private PlatformBookingPurgeTxService purgeTx;
    @Mock private NotificationOutboxRepository outbox;
    @Mock private PlatformAuditService audit;
    @InjectMocks private PassengerDataPurgeService service;

    @Test
    @SuppressWarnings("unchecked")
    void purgesEachCandidateIsolatesFailuresAndAuditsCountsOnly() {
        given(settings.paxRetentionDays()).willReturn(90);
        given(bookings.findPurgeCandidateIds(eq(PlatformBookingPurgeTxService.TERMINAL), any())).willReturn(List.of("a", "b", "c"));
        given(purgeTx.purge(eq("a"), any(), eq(90))).willReturn(true);
        given(purgeTx.purge(eq("b"), any(), eq(90))).willReturn(false);
        given(purgeTx.purge(eq("c"), any(), eq(90))).willThrow(new IllegalStateException("boom with Rahul Sharma"));
        given(outbox.clearOldBodies(any())).willReturn(4);

        service.run();

        ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
        verify(bookings).findPurgeCandidateIds(any(), createdBefore.capture());
        assertThat(createdBefore.getValue()).isBefore(Instant.now().minus(Duration.ofDays(89)));
        ArgumentCaptor<Instant> bodies = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).clearOldBodies(bodies.capture());
        assertThat(bodies.getValue()).isBefore(Instant.now().minus(Duration.ofDays(6)));
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordSystem(eq(PlatformAuditAction.PASSENGER_DATA_PURGE), any(), any(), detail.capture());
        assertThat(detail.getValue()).containsEntry("bookingsPurged", 1).containsEntry("bookingsFailed", 1)
                .containsEntry("examined", 3).containsEntry("outboxBodiesCleared", 4);
    }

    @Test
    void openStatusesAreNotInTheScannedSet() {
        assertThat(PlatformBookingPurgeTxService.TERMINAL).doesNotContain(
                com.voyra.crm.enums.PlatformBookingStatus.REVIEWING, com.voyra.crm.enums.PlatformBookingStatus.HELD,
                com.voyra.crm.enums.PlatformBookingStatus.BOOKING, com.voyra.crm.enums.PlatformBookingStatus.UNKNOWN,
                com.voyra.crm.enums.PlatformBookingStatus.CANCELLING);
    }
}
