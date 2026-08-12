package com.voyra.crm.migration;

import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies any pending tenant-schema migrations to every existing tenant on every startup.
 * Without this, a tenant migration added after a tenant was created would never reach it.
 * Runs after the seed runner (default order 0) so newly seeded tenants are included.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(100)
public class TenantMigrationStartupRunner implements ApplicationRunner {

    private final TenantRepository tenantRepository;
    private final TenantFlywayMigrator tenantFlywayMigrator;

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = tenantRepository.findAll();
        if (tenants.isEmpty()) {
            log.debug("No existing tenants to migrate");
            return;
        }
        log.info("Applying pending tenant migrations for {} tenant(s)", tenants.size());
        for (Tenant tenant : tenants) {
            try {
                tenantFlywayMigrator.migrate(tenant.getId());
            } catch (Exception e) {
                log.error("Failed to migrate tenant schema {}: {}", tenant.getId(), e.getMessage(), e);
                throw new IllegalStateException("Tenant migration failed for " + tenant.getId(), e);
            }
        }
        log.info("Tenant migration startup complete");
    }
}
