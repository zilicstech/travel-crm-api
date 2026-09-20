package com.voyra.crm.util;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadServiceRepository;

import java.util.List;

/**
 * Whether an agent who is not a lead's own assignedTo may still reach it, because they are
 * personally assigned to - or manage the type of - at least one service on it. Shared by every
 * lead-scoped service (LeadService, InvoiceService, ...) so a Visa agent quoting or invoicing
 * their own service on someone else's lead is never blocked by a lead-ownership check that has
 * nothing to do with which service they actually handle.
 */
public final class LeadAccessChecker {

    private LeadAccessChecker() {
    }

    public static boolean hasServiceAccess(LeadServiceRepository leadServiceRepository, AgentRepository agentRepository,
                                            String leadId, String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return false;
        }
        List<LeadService> services = leadServiceRepository.findByLeadIdOrderBySortOrderAsc(leadId);
        return services.stream().anyMatch(s ->
                agentId.equals(s.getAssignedAgentId()) || agent.getManageableServices().contains(s.getType()));
    }
}
