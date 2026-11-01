package com.voyra.crm.scheduler;

import com.voyra.crm.service.GatewayReconciliationService;
import com.voyra.crm.service.NotificationOutboxService;
import com.voyra.crm.service.booking.PassengerDataPurgeService;
import com.voyra.crm.service.SubscriptionLifecycleService;
import com.voyra.crm.service.booking.PlatformBookingPoller;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** The thin scheduled jobs: each tick hands the work to its service. */
@ExtendWith(MockitoExtension.class)
class DelegatingJobsTest {

    @Mock private GatewayReconciliationService reconciliationService;
    @Mock private NotificationOutboxService outboxService;
    @Mock private PassengerDataPurgeService purgeService;
    @Mock private PlatformBookingPoller poller;
    @Mock private SubscriptionLifecycleService lifecycleService;

    @Test
    void eachJobDelegatesItsTick() {
        new GatewayReconciliationJob(reconciliationService).run();
        new NotificationOutboxJob(outboxService).run();
        new PassengerDataPurgeJob(purgeService).run();
        new PlatformBookingPollJob(poller).run();
        new SubscriptionLifecycleJob(lifecycleService).run();

        verify(reconciliationService).reconcile();
        verify(outboxService).drainDue();
        verify(purgeService).run();
        verify(poller).poll();
        verify(lifecycleService).runDaily();
    }

    @Test
    void exchangeRateFetchOnlyWarnsThatNoProviderIsWired() {
        try (LogCapture log = new LogCapture(ExchangeRateFetchJob.class)) {
            new ExchangeRateFetchJob().run();
            assertThat(log.lines()).singleElement().asString().startsWith("WARN Exchange rate fetch job fired but no live provider is configured");
        }
    }
}
