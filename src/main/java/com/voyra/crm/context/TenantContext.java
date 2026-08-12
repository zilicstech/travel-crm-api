package com.voyra.crm.context;

/**
 * ThreadLocal holder for the active tenant (Agency) of the current request. Set by the
 * JWT filter, or explicitly around a REQUIRES_NEW cross-tenant unit of work. Request
 * threads are pooled and reused, so every setter of this must clear it in a finally block.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
