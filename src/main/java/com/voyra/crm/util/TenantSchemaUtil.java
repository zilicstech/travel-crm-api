package com.voyra.crm.util;

/** Maps a tenant (Agency) id to its Postgres schema name. */
public final class TenantSchemaUtil {

    private static final String SCHEMA_PREFIX = "tenant_";

    private TenantSchemaUtil() {
    }

    /** Tenant IDs are stored uppercase; schema names are always lowercase. */
    public static String toSchemaName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return SCHEMA_PREFIX + tenantId.toLowerCase();
    }
}
