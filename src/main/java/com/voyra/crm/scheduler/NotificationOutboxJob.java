package com.voyra.crm.scheduler;

import com.voyra.crm.config.SmtpConfigured;
import com.voyra.crm.service.NotificationOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: send due outbox emails. Safe on several instances (rows are claimed one by one). Public schema only. */
@Component
@RequiredArgsConstructor
@Conditional(SmtpConfigured.class)
public class NotificationOutboxJob {

    private final NotificationOutboxService outboxService;

    @Scheduled(cron = "${app.notifications.outbox.cron:0 * * * * *}")
    public void run() {
        outboxService.drainDue();
    }
}
