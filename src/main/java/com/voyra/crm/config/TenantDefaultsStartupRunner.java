package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.TenantDefaultsSeeder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backfills default settings and vendors for any tenant that has none - an agency created before
 * creation-time seeding existed, or one whose creation-time seed failed. Runs after tenant schema
 * migrations (order 100) and before demo business data (order 200). Replaces the separate
 * AgencySettingSeedRunner / VendorSeedRunner, which seeded without a lock or transaction.
 *
 * Never fatal: a tenant that fails to seed is logged and skipped, so one bad tenant cannot keep
 * the whole platform from booting; the next boot retries it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(150)
public class TenantDefaultsStartupRunner implements ApplicationRunner {

    private final TenantRepository tenantRepository;
    private final TenantDefaultsSeeder tenantDefaultsSeeder;

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = tenantRepository.findAll();
        int failed = 0;
        for (Tenant tenant : tenants) {
            TenantContext.setTenantId(tenant.getId());
            try {
                tenantDefaultsSeeder.seedCurrentTenant();
            } catch (RuntimeException e) {
                failed++;
                log.error("Could not seed defaults for tenant {}; will retry on next boot", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Tenant defaults checked for {} tenant(s), {} failed", tenants.size(), failed);
    }
}
