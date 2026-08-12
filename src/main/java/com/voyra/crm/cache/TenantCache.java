package com.voyra.crm.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path tenant validity lookup, avoiding a DB hit on every authenticated request. */
public final class TenantCache {

    private static final ConcurrentHashMap<String, TenantInfo> CACHE = new ConcurrentHashMap<>();

    private TenantCache() {
    }

    public record TenantInfo(String name, boolean isActive) {
    }

    public static void put(String tenantId, String name, boolean isActive) {
        if (tenantId != null && !tenantId.isBlank()) {
            CACHE.put(tenantId.trim().toUpperCase(), new TenantInfo(name == null ? "" : name, isActive));
        }
    }

    public static boolean isValidTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        TenantInfo info = CACHE.get(tenantId.trim().toUpperCase());
        return info != null && info.isActive();
    }

    public static void loadFrom(Collection<TenantEntry> entries) {
        CACHE.clear();
        entries.forEach(e -> put(e.tenantId(), e.name(), e.isActive()));
    }

    public record TenantEntry(String tenantId, String name, boolean isActive) {
    }
}
