package com.voyra.crm.cache;

import com.voyra.crm.enums.TenantStatus;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path tenant status lookup, avoiding a DB hit on every authenticated request. */
public final class TenantCache {

    private static final ConcurrentHashMap<String, TenantInfo> CACHE = new ConcurrentHashMap<>();

    private TenantCache() {
    }

    public record TenantInfo(String name, TenantStatus status) {
    }

    public static void put(String tenantId, String name, TenantStatus status) {
        if (tenantId != null && !tenantId.isBlank()) {
            CACHE.put(key(tenantId), new TenantInfo(name == null ? "" : name, status));
        }
    }

    public static Optional<TenantStatus> status(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CACHE.get(key(tenantId))).map(TenantInfo::status);
    }

    /** SUSPENDED is still valid - it authenticates and reads; only DEACTIVATED (or unknown) is refused. */
    public static boolean isValidTenant(String tenantId) {
        return status(tenantId).filter(s -> s != TenantStatus.DEACTIVATED).isPresent();
    }

    public static void loadFrom(Collection<TenantEntry> entries) {
        CACHE.clear();
        entries.forEach(e -> put(e.tenantId(), e.name(), e.status()));
    }

    private static String key(String tenantId) {
        return tenantId.trim().toUpperCase();
    }

    public record TenantEntry(String tenantId, String name, TenantStatus status) {
    }
}
