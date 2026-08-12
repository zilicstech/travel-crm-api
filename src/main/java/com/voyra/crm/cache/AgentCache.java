package com.voyra.crm.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path Agent liveness lookup (per-request re-check so a deactivated agent's still-valid JWT stops working immediately). */
public final class AgentCache {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private AgentCache() {
    }

    public static void put(String agentId, boolean isActive) {
        if (agentId != null && !agentId.isBlank()) {
            CACHE.put(agentId.trim().toUpperCase(), isActive);
        }
    }

    public static void remove(String agentId) {
        if (agentId != null) {
            CACHE.remove(agentId.trim().toUpperCase());
        }
    }

    public static boolean isActive(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(CACHE.get(agentId.trim().toUpperCase()));
    }

    public static void loadFrom(Collection<AgentEntry> entries) {
        CACHE.clear();
        entries.forEach(e -> put(e.agentId(), e.isActive()));
    }

    public record AgentEntry(String agentId, boolean isActive) {
    }
}
