package com.voyra.crm.migration;

import com.voyra.crm.util.TenantSchemaUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Runs Flyway tenant migrations for a single Agency's schema (tenant_&lt;id lowercased&gt;). */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantFlywayMigrator {

    private final DataSource dataSource;

    public void migrate(String tenantId) {
        String schema = TenantSchemaUtil.toSchemaName(tenantId);
        log.info("Running tenant migrations for schema: {}", schema);

        ensureTrigramExtensionInPublic();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/tenant")
                // "public" is on the migration search_path (tenant first) for the same reason it
                // is on the runtime one (TenantSearchPathUtil): shared extension objects such as
                // pg_trgm's gin_trgm_ops live there. defaultSchema keeps every unqualified
                // CREATE and the schema history table in the tenant schema.
                .schemas(schema, "public")
                .defaultSchema(schema)
                .createSchemas(true)
                .load()
                .migrate();

        log.info("Tenant migrations completed for schema: {}", schema);
    }

    /**
     * V15 runs {@code CREATE EXTENSION IF NOT EXISTS pg_trgm} with no schema, which installs the
     * extension into whichever tenant schema happens to migrate first. An extension exists once
     * per database, so every later tenant then skips the CREATE yet cannot resolve
     * {@code gin_trgm_ops} and its V15 fails - a second Agency could never be created. V15 is
     * already applied on live tenants and its checksum must not change, so the extension is put
     * (or moved) into {@code public} here instead, before any tenant migration runs. Idempotent.
     */
    private void ensureTrigramExtensionInPublic() {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // Two agencies created at once must not race on the same CREATE/ALTER.
                stmt.execute("SELECT pg_advisory_xact_lock(hashtext('voyra.pg_trgm'))");
                String current = null;
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace "
                                + "WHERE e.extname = 'pg_trgm'")) {
                    if (rs.next()) {
                        current = rs.getString(1);
                    }
                }
                if (current == null) {
                    stmt.execute("CREATE EXTENSION pg_trgm SCHEMA public");
                    log.info("Installed pg_trgm into public");
                } else if (!"public".equals(current)) {
                    stmt.execute("ALTER EXTENSION pg_trgm SET SCHEMA public");
                    log.info("Moved pg_trgm from schema {} to public", current);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            // Not fatal: tenants already past V15 do not need it, and a managed Postgres (e.g.
            // DigitalOcean) forbids moving the extension because its functions belong to the
            // platform's own role. A tenant that still needs V15 will fail with a clear error;
            // relocate once by hand: DROP the tenant's idx_client_name_trgm, DROP EXTENSION
            // pg_trgm, CREATE EXTENSION pg_trgm SCHEMA public, then recreate the index.
            log.warn("Could not place pg_trgm in public ({}). Agencies created before this fix keep "
                    + "it inside their own schema; new agencies need it in public - see "
                    + "TenantFlywayMigrator.ensureTrigramExtensionInPublic.", e.getMessage());
        }
    }
}
