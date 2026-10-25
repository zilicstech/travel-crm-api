package com.voyra.crm.scheduler;

import com.voyra.crm.service.GatewayReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every few minutes: reconcile PENDING gateway top-ups with the gateway. Public schema only. */
@Component
@RequiredArgsConstructor
public class GatewayReconciliationJob {

    private final GatewayReconciliationService reconciliationService;

    @Scheduled(cron = "${app.payments.reconcile.cron:0 */5 * * * *}")
    public void run() {
        reconciliationService.reconcile();
    }
}
