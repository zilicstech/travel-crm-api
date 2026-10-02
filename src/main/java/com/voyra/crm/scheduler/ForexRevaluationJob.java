package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.ForexRevaluationTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §3.5 - month-end mark-to-market revaluation of every open
 * foreign-currency supplier bill. Same shape as {@link LedgerIntegrityJob}/{@code
 * RevenueRecognitionJob}: no request-scoped {@link TenantContext} on a scheduled method, so this
 * iterates every tenant explicitly, switches context with a try/finally restore, and delegates
 * the actual posting to {@link ForexRevaluationTenantService}, a separate {@code REQUIRES_NEW}
 * bean. A single tenant's failure logs and continues rather than stopping the others.
 */
@Component
@ConditionalOnProperty(name = "app.accounting.forex-revaluation.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ForexRevaluationJob {

    private final TenantRepository tenantRepository;
    private final ForexRevaluationTenantService revaluationService;

    /** 02:00 on the 1st of the month, server time - after the prior month's business has fully posted. */
    @Scheduled(cron = "${app.accounting.forex-revaluation.cron:0 0 2 1 * *}")
    public void run() {
        LocalDate monthEnd = LocalDate.now().minusDays(1);
        List<Tenant> tenants = tenantRepository.findAll();
        int revaluedTotal = 0;
        int tenantsProcessed = 0;
        for (Tenant tenant : tenants) {
            if (!Boolean.TRUE.equals(tenant.getIsActive())) {
                continue;
            }
            TenantContext.setTenantId(tenant.getId());
            try {
                ForexRevaluationTenantService.RevaluationResult result = revaluationService.revalueOpenForeignBills(monthEnd);
                tenantsProcessed++;
                revaluedTotal += result.revalued();
            } catch (Exception e) {
                log.error("Forex revaluation failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Forex revaluation complete: {} tenant(s) processed, {} bill(s) revalued", tenantsProcessed, revaluedTotal);
    }
}
