package com.voyra.crm.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path Platform Admin (SUPER_ADMIN) liveness lookup. */
public final class PlatformAdminCache {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private PlatformAdminCache() {
    }

    public static void put(String adminId, boolean isActive) {
        if (adminId != null && !adminId.isBlank()) {
            CACHE.put(adminId.trim().toUpperCase(), isActive);
        }
    }

    public static boolean isActive(String adminId) {
        if (adminId == null || adminId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(CACHE.get(adminId.trim().toUpperCase()));
    }

    public static void loadFrom(Collection<AdminEntry> entries) {
        CACHE.clear();
        entries.forEach(e -> put(e.adminId(), e.isActive()));
    }

    public record AdminEntry(String adminId, boolean isActive) {
    }
}
