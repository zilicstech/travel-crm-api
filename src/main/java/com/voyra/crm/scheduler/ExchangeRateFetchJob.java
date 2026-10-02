package com.voyra.crm.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rule 3.2.2 - tenant-FREE (rates are global, unlike {@code LedgerIntegrityJob} /
 * {@code RevenueRecognitionJob}, which iterate every tenant). No live rate feed is wired in this
 * codebase (Rule 3.2.1 keeps the build free of a paid external dependency) - this is a stub that
 * logs its own absence, left disabled by default so it does nothing until a real provider lands
 * behind {@code app.fx.fetch-job.enabled}. {@link com.voyra.crm.service.ManualExchangeRateProvider}
 * plus the {@code exchange_rate} table is the actual deliverable; this class is the seam a live
 * fetch implementation plugs into later, per BACKEND_BLUEPRINT.md §8.6 (any real HTTP call here
 * must happen outside a transaction, with persistence done in a short separate transactional
 * method afterward).
 */
@Component
@ConditionalOnProperty(name = "app.fx.fetch-job.enabled", havingValue = "true")
@Slf4j
public class ExchangeRateFetchJob {

    @Scheduled(cron = "${app.fx.fetch-job.cron:0 0 2 * * *}")
    public void run() {
        log.warn("Exchange rate fetch job fired but no live provider is configured - "
                + "record today's rates manually, or implement a real ExchangeRateProvider "
                + "and wire its fetch call here, outside any transaction.");
    }
}
