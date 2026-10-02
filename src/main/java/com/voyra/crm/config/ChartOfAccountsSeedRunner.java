package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.ChartOfAccountsSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reaches every tenant on every boot and upserts its chart of accounts by code
 * (ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.4) - safe to re-run, and the only way a tenant
 * migrated before V42 existed, or a new system account added later, ever gets the missing rows.
 * Runs right after tenant schema migration (order 100) and before every other tenant seed, since
 * nothing else here depends on agency settings or vendors existing first.
 *
 * <p>No enclosing transaction exists at startup, so - unlike a runtime cross-tenant call such as
 * {@code AgencyService#createAgency} - a plain {@code TenantContext} switch around the call is
 * enough; {@link ChartOfAccountsSeedService#seed} is still REQUIRES_NEW regardless, which is a
 * no-op distinction here and the exact precedent {@code VendorSeedRunner} already sets.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(110)
public class ChartOfAccountsSeedRunner implements ApplicationRunner {

    private final TenantRepository tenantRepository;
    private final ChartOfAccountsSeedService chartOfAccountsSeedService;

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            TenantContext.setTenantId(tenant.getId());
            try {
                chartOfAccountsSeedService.seed();
            } catch (Exception e) {
                log.error("Chart of accounts seed failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Chart of accounts seed pass complete for {} tenant(s)", tenants.size());
    }
}
