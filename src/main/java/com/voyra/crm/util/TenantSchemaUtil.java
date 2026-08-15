package com.voyra.crm.util;

/** Maps a tenant (Agency) id to its Postgres schema name. */
public final class TenantSchemaUtil {

    private static final String SCHEMA_PREFIX = "tenant_";

    private TenantSchemaUtil() {
    }

    /**
     * Tenant IDs are UUID strings (e.g. {@code f47ac10b-58cc-4372-a567-0e02b2c3d479}); dashes
     * are not safe/idiomatic in an unquoted Postgres schema identifier built via string
     * concatenation, so they're stripped. Schema names are always lowercase, e.g.
     * {@code tenant_f47ac10b58cc4372a5670e02b2c3d479} (39 chars, well under the 63-byte limit).
     */
    public static String toSchemaName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return SCHEMA_PREFIX + tenantId.toLowerCase().replace("-", "");
    }
}
