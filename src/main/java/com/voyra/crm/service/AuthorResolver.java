package com.voyra.crm.service;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves "who is acting right now" for author-stamped rows (lead notes, customer
 * interactions) that AGENCY_OWNER, AGENT and ACCOUNTANT can write. The author id column is
 * named *_agent_id for historical reasons but holds either a real Agent/Accountant id or the
 * Owner's tenantId (the Owner's own "user id") - both are plain opaque strings, never
 * FK-constrained.
 */
@Service
@RequiredArgsConstructor
public class AuthorResolver {

    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;

    public record AuthorInfo(String id, String name) {
    }

    public AuthorInfo resolveCurrentAuthor() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isStaffUser()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorInfo(agent.getId(), agent.getName());
        }
        Tenant tenant = tenantRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + principal.userId()));
        return new AuthorInfo(tenant.getId(), tenant.getOwnerName());
    }
}
