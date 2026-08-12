package com.voyra.crm.migration;

import com.voyra.crm.util.TenantSchemaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Runs Flyway tenant migrations for a single Agency's schema (tenant_&lt;id lowercased&gt;). */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantFlywayMigrator {

    private final DataSource dataSource;

    public void migrate(String tenantId) {
        String schema = TenantSchemaUtil.toSchemaName(tenantId);
        log.info("Running tenant migrations for schema: {}", schema);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .createSchemas(true)
                .load()
                .migrate();

        log.info("Tenant migrations completed for schema: {}", schema);
    }
}
