package com.voyra.crm.service;

import com.voyra.crm.dto.LoginRequest;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Every login method checks existence, active flag, and password, then returns one
 * identical generic failure for all three - the caller must never be able to tell which
 * one failed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String GENERIC_FAILURE = "Invalid email or password";

    private final PlatformAdminRepository platformAdminRepository;
    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public LoginResponse loginPlatformAdmin(LoginRequest request) {
        Optional<PlatformAdmin> admin = platformAdminRepository.findByEmailIgnoreCase(request.getEmail());
        if (admin.isEmpty() || !Boolean.TRUE.equals(admin.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), admin.get().getPassword())) {
            log.warn("Platform admin login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        }
        PlatformAdmin a = admin.get();
        log.info("Platform admin login succeeded: {}", a.getId());
        return LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(a.getId())
                .name(a.getName())
                .role(UserType.SUPER_ADMIN.name())
                .token(jwtService.generateToken(a.getEmail(), UserType.SUPER_ADMIN, a.getId(), null))
                .build();
    }

    @Transactional(readOnly = true)
    public LoginResponse loginOwner(LoginRequest request) {
        Optional<Tenant> tenant = tenantRepository.findByOwnerEmailIgnoreCase(request.getEmail());
        if (tenant.isEmpty() || !Boolean.TRUE.equals(tenant.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), tenant.get().getPassword())) {
            log.warn("Owner login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        }
        Tenant t = tenant.get();
        log.info("Owner login succeeded: tenantId={}", t.getId());
        return LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(t.getId())
                .name(t.getOwnerName())
                .role(UserType.AGENCY_OWNER.name())
                .tenantId(t.getId())
                .token(jwtService.generateToken(t.getOwnerEmail(), UserType.AGENCY_OWNER, t.getId(), t.getId()))
                .build();
    }

    @Transactional(readOnly = true)
    public LoginResponse loginAgent(LoginRequest request) {
        Optional<Agent> agent = agentRepository.findByEmailIgnoreCase(request.getEmail());
        if (agent.isEmpty() || !Boolean.TRUE.equals(agent.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), agent.get().getPassword())) {
            log.warn("Agent login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        }
        Agent a = agent.get();
        log.info("Agent login succeeded: agentId={}", a.getId());
        return LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(a.getId())
                .name(a.getName())
                .role(UserType.AGENT.name())
                .tenantId(a.getTenantId())
                .token(jwtService.generateToken(a.getEmail(), UserType.AGENT, a.getId(), a.getTenantId()))
                .build();
    }
}
