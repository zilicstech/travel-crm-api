package com.voyra.crm.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V37 on real Postgres: each test migrates a fresh schema to V36, seeds the old shape, then migrates to V37. Needs Docker
 * (Testcontainers), like the other *IT classes, so it is outside {@code mvn verify}.
 */
@Testcontainers
@Tag("integration")
class PlatformSuppliersMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String schema;

    @BeforeEach
    void freshSchemaAtV36() {
        schema = "mig_" + SEQ.incrementAndGet();
        migrateTo("36");
        exec("insert into platform_supplier_credential (id, provider, environment, base_url, api_key_encrypted, is_active, name) "
                + "values ('T1', 'TRIPJACK', 'PROD', 'https://api.tripjack.com', 'enc', true, 'Tripjack')");
        exec("insert into platform_supplier_product (credential_id, product, commission_estimate_pct) values ('T1', 'FLIGHT', 5), ('T1', 'HOTEL', 5)");
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).locations("classpath:db/migration/public").target(version).load().migrate();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("set search_path to " + schema);
        }
        return c;
    }

    private void exec(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String query(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void addsTheTogglesOffAndTheMockSupplierAndBackfillsMockBookings() {
        exec("insert into platform_booking (id, tenant_id, product, status, environment) values ('B1', 'T', 'FLIGHT', 'TICKETED', 'MOCK')");

        migrateTo("37");

        assertThat(query("select count(*) from platform_supplier_product where search_enabled or booking_enabled")).isEqualTo("0");
        assertThat(query("select provider||'|'||environment||'|'||base_url||'|'||api_key_encrypted from platform_supplier_credential where name = 'Mock supplier'"))
                .isEqualTo("MOCK|MOCK|mock://local|");
        assertThat(query("select count(*) from platform_supplier_product p join platform_supplier_credential c on c.id = p.credential_id where c.provider = 'MOCK'"))
                .isEqualTo("2");
        assertThat(query("select c.name from platform_booking b join platform_supplier_credential c on c.id = b.credential_id")).isEqualTo("Mock supplier");
        // the old shape is kept for instances still running the old code, and a new write needs no estimate
        assertThat(query("select count(*) from platform_supplier_product where commission_estimate_pct = 5 and credential_id = 'T1'")).isEqualTo("2");
        exec("insert into platform_supplier_product (credential_id, product) values ('T1', 'FLIGHT') on conflict do nothing");
        // a second Tripjack row now inserts; a name differing only in case does not
        exec("insert into platform_supplier_credential (id, provider, environment, base_url, api_key_encrypted, name) "
                + "values ('T2', 'TRIPJACK', 'UAT', 'https://apitest.tripjack.com', 'enc', 'Tripjack test')");
        assertThatThrownBy(() -> exec("insert into platform_supplier_credential (id, provider, environment, base_url, api_key_encrypted, name) "
                + "values ('T3', 'TRIPJACK', 'UAT', 'https://apitest.tripjack.com', 'enc', 'TRIPJACK')"))
                .hasStackTraceContaining("uq_platform_supplier_credential_name");
    }

    @Test
    void refusesWhileACappedCommissionRuleExceedsItsEstimate() {
        exec("insert into pricing_rule (id, kind, product, name, pct, allow_above_earned, created_by) values ('R1', 'COMMISSION', 'FLIGHT', 'Six', 6, false, 'SA')");

        assertThatThrownBy(() -> migrateTo("37")).hasStackTraceContaining("V37: a commission rule");
    }

    @Test
    void refusesWhileDiscountBaseIsNotGross() {
        exec("update platform_setting set value = 'BASE' where key = 'discount_base'");

        assertThatThrownBy(() -> migrateTo("37")).hasStackTraceContaining("V37: discount_base is not GROSS");
    }
}
