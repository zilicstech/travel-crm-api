package com.voyra.crm.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/** Applies the correct Postgres search_path to a raw JDBC connection for a given tenant. */
public final class TenantSearchPathUtil {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("tenant_[a-z0-9_]+");

    private TenantSearchPathUtil() {
    }

    public static String toSchemaPath(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "public";
        }
        String schema = TenantSchemaUtil.toSchemaName(tenantId);
        // Schema name is concatenated into DDL-ish SQL, so it MUST be validated against a
        // strict allowlist pattern. Never relax this regex - it is the only thing standing
        // between the tenant identifier and SQL injection via search_path.
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            return "public";
        }
        return schema + ", public";
    }

    public static void applyOnConnection(Connection conn, String tenantId) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + toSchemaPath(tenantId));
        }
    }
}
