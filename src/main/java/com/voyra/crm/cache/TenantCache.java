package com.voyra.crm.cache;

import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.models.Entitlements;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path tenant status and entitlements lookup, avoiding a DB hit on every authenticated request. */
public final class TenantCache {

    private static final ConcurrentHashMap<String, TenantInfo> CACHE = new ConcurrentHashMap<>();

    private TenantCache() {
    }

    /** entitlements null = not resolved yet (EntitlementService loads it on first use). */
    public record TenantInfo(String name, TenantStatus status, Entitlements entitlements, Instant entitlementsAt) {
    }

    /** Keeps any cached entitlements - a rename or status change does not touch the subscription. */
    public static void put(String tenantId, String name, TenantStatus status) {
        if (tenantId != null && !tenantId.isBlank()) {
            CACHE.compute(key(tenantId), (k, old) ->
                    new TenantInfo(name == null ? "" : name, status, old == null ? null : old.entitlements(),
                            old == null ? null : old.entitlementsAt()));
        }
    }

    /** Called by EntitlementService, in the same method as every subscription change. */
    public static void putEntitlements(String tenantId, Entitlements entitlements) {
        if (tenantId != null && !tenantId.isBlank()) {
            CACHE.compute(key(tenantId), (k, old) -> old == null
                    ? new TenantInfo("", null, entitlements, Instant.now())
                    : new TenantInfo(old.name(), old.status(), entitlements, Instant.now()));
        }
    }

    public static Optional<Entitlements> entitlements(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CACHE.get(key(tenantId))).map(TenantInfo::entitlements);
    }

    /** When the cached entitlements were stored; empty when none. */
    public static Optional<Instant> entitlementsAt(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CACHE.get(key(tenantId))).map(TenantInfo::entitlementsAt);
    }

    /** Test hook: pretend the cached entitlements were stored {@code age} ago. */
    public static void backdateEntitlements(String tenantId, java.time.Duration age) {
        CACHE.computeIfPresent(key(tenantId), (k, o) -> new TenantInfo(o.name(), o.status(), o.entitlements(), Instant.now().minus(age)));
    }

    public static void remove(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            CACHE.remove(key(tenantId));
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
