package com.voyra.crm.models;

import java.math.BigDecimal;

/**
 * Pre-aggregated per-agent metrics, loaded for a whole set of agents in three grouped
 * queries rather than eight queries per agent. Internal computation model - never
 * serialized, never persisted (blueprint §1, models/).
 */
public record AgentStats(
        long leadsAssigned,
        long bookedLeads,
        long activeLeads,
        long quotationsSent,
        long pendingFollowUps,
        long notesLogged,
        long bookingsCount,
        BigDecimal totalRevenue,
        BigDecimal totalProfit
) {
    public static AgentStats empty() {
        return new AgentStats(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
