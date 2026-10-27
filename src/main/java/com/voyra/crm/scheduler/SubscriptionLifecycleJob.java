package com.voyra.crm.scheduler;

import com.voyra.crm.service.SubscriptionLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily: subscription expiries, renewal invoices, past-due and read-only transitions, expiry notices. Public schema only. */
@Component
@RequiredArgsConstructor
public class SubscriptionLifecycleJob {

    private final SubscriptionLifecycleService lifecycleService;

    @Scheduled(cron = "${app.subscriptions.lifecycle.cron:0 15 0 * * *}")
    public void run() {
        lifecycleService.runDaily();
    }
}
