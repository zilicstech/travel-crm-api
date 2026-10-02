package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.LedgerIntegrityCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rule 1.7.3 - nightly, per tenant: every POSTED journal entry balances, and the whole trial
 * balance nets to zero. Logs an error naming the entry on failure; never repairs automatically.
 *
 * <p>This is the FIRST {@code @Scheduled} job in this codebase (the {@code scheduler/} package
 * was empty before this). A scheduled method has no request-scoped
 * {@link com.voyra.crm.context.TenantContext} - it iterates every tenant explicitly, switches
 * context around each one with a try/finally restore, and delegates the actual DB read to
 * {@link LedgerIntegrityCheckService}, a separate {@code REQUIRES_NEW} bean, for the same
 * self-invocation-bypasses-the-proxy reason every other cross-tenant call site in this codebase
 * does (blueprint §3.5).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerIntegrityJob {

    private final TenantRepository tenantRepository;
    private final LedgerIntegrityCheckService ledgerIntegrityCheckService;

    /** 01:30 server time, daily - after the business day closes, before the next one opens. */
    @Scheduled(cron = "0 30 1 * * *")
    public void run() {
        List<Tenant> tenants = tenantRepository.findAll();
        int checked = 0;
        int clean = 0;
        for (Tenant tenant : tenants) {
            if (!Boolean.TRUE.equals(tenant.getIsActive())) {
                continue;
            }
            TenantContext.setTenantId(tenant.getId());
            try {
                LedgerIntegrityCheckService.IntegrityResult result = ledgerIntegrityCheckService.check();
                checked++;
                if (result.isClean()) {
                    clean++;
                } else {
                    if (!result.unbalancedEntryNumbers().isEmpty()) {
                        log.error("Ledger integrity: tenant {} has unbalanced journal entries: {}",
                                tenant.getId(), result.unbalancedEntryNumbers());
                    }
                    if (result.trialBalanceNet().signum() != 0) {
                        log.error("Ledger integrity: tenant {} trial balance does not net to zero - net {}",
                                tenant.getId(), result.trialBalanceNet());
                    }
                }
            } catch (Exception e) {
                log.error("Ledger integrity check failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Ledger integrity check complete: {}/{} tenant(s) clean", clean, checked);
    }
}
