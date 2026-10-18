package com.voyra.crm.service;

import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login is by email across three tables (agency owners, staff, platform admins), so an email
 * may belong to only one of them - otherwise the password alone decides who signs in.
 */
@Service
@RequiredArgsConstructor
public class LoginEmailService {

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PlatformAdminRepository platformAdminRepository;

    @Transactional(readOnly = true)
    public void assertFree(String email) {
        if (email != null && (tenantRepository.existsByOwnerEmailIgnoreCase(email)
                || agentRepository.existsByEmailIgnoreCase(email)
                || platformAdminRepository.existsByEmailIgnoreCase(email))) {
            throw new IllegalArgumentException("This email is already used by another login - use a different email");
        }
    }
}
