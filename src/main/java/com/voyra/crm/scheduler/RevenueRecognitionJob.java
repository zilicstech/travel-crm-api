package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.RevenueRecognitionTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §2.3 - the departure-date revenue recognition job.
 * Same shape as {@link LedgerIntegrityJob}: a scheduled method has no request-scoped
 * {@link TenantContext}, so this iterates every tenant explicitly, switches context around each
 * one with a try/finally restore, and delegates the actual posting to
 * {@link RevenueRecognitionTenantService}, a separate {@code REQUIRES_NEW} bean, for the
 * self-invocation-bypasses-the-proxy reason every other cross-tenant call site in this codebase
 * follows (blueprint §3.5).
 *
 * <p>Rule 2.3.3 - unlike {@code TenantMigrationStartupRunner}, a single tenant's failure here
 * must not stop recognition for every other agency, so this logs and continues rather than
 * rethrowing. Rule 2.3.4 - {@code app.accounting.revenue-recognition.enabled} must be {@code
 * false} on every instance but one in an autoscaled deployment; see {@code .env.example}.
 */
@Component
@ConditionalOnProperty(name = "app.accounting.revenue-recognition.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionJob {

    private final TenantRepository tenantRepository;
    private final RevenueRecognitionTenantService recognitionService;

    /** 01:30 server time, daily - after the business day closes, before the next one opens.
     *  Same slot as {@link LedgerIntegrityJob}; the two never race because each runs in its own
     *  REQUIRES_NEW transaction per tenant. */
    @Scheduled(cron = "${app.accounting.revenue-recognition.cron:0 30 1 * * *}")
    public void run() {
        LocalDate asOf = LocalDate.now();
        List<Tenant> tenants = tenantRepository.findAll();
        int recognizedTotal = 0;
        int tenantsProcessed = 0;
        for (Tenant tenant : tenants) {
            if (!Boolean.TRUE.equals(tenant.getIsActive())) {
                continue;
            }
            TenantContext.setTenantId(tenant.getId());
            try {
                RevenueRecognitionTenantService.RecognitionResult result = recognitionService.recognizeDue(asOf);
                tenantsProcessed++;
                recognizedTotal += result.recognized();
            } catch (Exception e) {
                log.error("Revenue recognition failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Revenue recognition complete: {} tenant(s) processed, {} invoice(s) recognized", tenantsProcessed, recognizedTotal);
    }
}
