package com.voyra.crm.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Hot-path Agent liveness lookup (per-request re-check so a deactivated agent's still-valid JWT stops working immediately). */
public final class AgentCache {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();
    /** Epoch second before which an agent's tokens are refused (their last password reset). */
    private static final ConcurrentHashMap<String, Long> VALID_AFTER = new ConcurrentHashMap<>();

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
            VALID_AFTER.remove(agentId.trim().toUpperCase());
        }
    }

    /** Refuses every token issued before {@code epochSecond}. */
    public static void revokeTokensBefore(String agentId, long epochSecond) {
        if (agentId != null && !agentId.isBlank()) {
            VALID_AFTER.put(agentId.trim().toUpperCase(), epochSecond);
        }
    }

    /** Active, and the token was issued at or after the agent's last password reset. */
    public static boolean isActive(String agentId, java.util.Date issuedAt) {
        if (!isActive(agentId)) {
            return false;
        }
        Long validAfter = VALID_AFTER.get(agentId.trim().toUpperCase());
        return validAfter == null || (issuedAt != null && issuedAt.toInstant().getEpochSecond() >= validAfter);
    }

    public static boolean isActive(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(CACHE.get(agentId.trim().toUpperCase()));
    }

    public static void loadFrom(Collection<AgentEntry> entries) {
        CACHE.clear();
        VALID_AFTER.clear();
        entries.forEach(e -> {
            put(e.agentId(), e.isActive());
            if (e.passwordChangedAt() != null) {
                revokeTokensBefore(e.agentId(), e.passwordChangedAt().getEpochSecond());
            }
        });
    }

    public record AgentEntry(String agentId, boolean isActive, java.time.Instant passwordChangedAt) {
        public AgentEntry(String agentId, boolean isActive) {
            this(agentId, isActive, null);
        }
    }
}
