package com.voyra.crm.scheduler;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 30 s: re-reads every tenant's status into this instance's TenantCache, so a suspend, deactivate or
 * approval made on another instance takes effect here without a restart. One query per run; cached
 * entitlements are kept. Public schema only.
 */
@Component
@RequiredArgsConstructor
public class TenantCacheRefreshJob {

    private final TenantRepository tenantRepository;

    @Scheduled(initialDelayString = "${app.tenant-cache.refresh-ms:30000}", fixedDelayString = "${app.tenant-cache.refresh-ms:30000}")
    public void run() {
        TenantCache.loadFrom(tenantRepository.findAll().stream()
                .map(t -> new TenantCache.TenantEntry(t.getId(), t.getAgencyName(), t.getStatus()))
                .toList());
    }
}
