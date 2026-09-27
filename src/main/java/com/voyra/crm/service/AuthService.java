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
 *
 * <p>{@link #login} is the one the UI uses: a single email+password box, with the principal
 * type worked out here rather than picked by the person signing in. The three type-specific
 * methods stay as their own endpoints - they are a documented API surface (PROGRESS.md) and
 * are what {@code login} itself is built from.
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

    /**
     * Resolves the principal from the credentials alone - no role is supplied by the caller.
     * The three identity stores are separate tables with no shared uniqueness constraint
     * between them, so one address could in principle exist in more than one; the order here
     * (platform admin, then agency owner, then agent/accountant) is the tie-break, most
     * privileged first, and is the whole reason this is a fixed sequence rather than a set.
     * Each attempt is a plain miss, not a failure, so a successful agent login does not log
     * two misleading "login failed" warnings for the stores checked before it.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        return tryPlatformAdmin(request)
                .or(() -> tryOwner(request))
                .or(() -> tryAgent(request))
                .orElseGet(() -> {
                    log.warn("Login failed for email: {}", request.getEmail());
                    return LoginResponse.failure(GENERIC_FAILURE);
                });
    }

    @Transactional(readOnly = true)
    public LoginResponse loginPlatformAdmin(LoginRequest request) {
        return tryPlatformAdmin(request).orElseGet(() -> {
            log.warn("Platform admin login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        });
    }

    @Transactional(readOnly = true)
    public LoginResponse loginOwner(LoginRequest request) {
        return tryOwner(request).orElseGet(() -> {
            log.warn("Owner login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        });
    }

    @Transactional(readOnly = true)
    public LoginResponse loginAgent(LoginRequest request) {
        return tryAgent(request).orElseGet(() -> {
            log.warn("Agent login failed for email: {}", request.getEmail());
            return LoginResponse.failure(GENERIC_FAILURE);
        });
    }

    /** Empty means "not this kind of principal, or wrong password" - never distinguished. */
    private Optional<LoginResponse> tryPlatformAdmin(LoginRequest request) {
        Optional<PlatformAdmin> admin = platformAdminRepository.findByEmailIgnoreCase(request.getEmail());
        if (admin.isEmpty() || !Boolean.TRUE.equals(admin.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), admin.get().getPassword())) {
            return Optional.empty();
        }
        PlatformAdmin a = admin.get();
        log.info("Platform admin login succeeded: {}", a.getId());
        return Optional.of(LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(a.getId())
                .name(a.getName())
                .role(UserType.SUPER_ADMIN.name())
                .token(jwtService.generateToken(a.getEmail(), UserType.SUPER_ADMIN, a.getId(), null))
                .build());
    }

    private Optional<LoginResponse> tryOwner(LoginRequest request) {
        Optional<Tenant> tenant = tenantRepository.findByOwnerEmailIgnoreCase(request.getEmail());
        if (tenant.isEmpty() || !Boolean.TRUE.equals(tenant.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), tenant.get().getPassword())) {
            return Optional.empty();
        }
        Tenant t = tenant.get();
        log.info("Owner login succeeded: tenantId={}", t.getId());
        return Optional.of(LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(t.getId())
                .name(t.getOwnerName())
                .role(UserType.AGENCY_OWNER.name())
                .tenantId(t.getId())
                .token(jwtService.generateToken(t.getOwnerEmail(), UserType.AGENCY_OWNER, t.getId(), t.getId()))
                .build());
    }

    private Optional<LoginResponse> tryAgent(LoginRequest request) {
        Optional<Agent> agent = agentRepository.findByEmailIgnoreCase(request.getEmail());
        if (agent.isEmpty() || !Boolean.TRUE.equals(agent.get().getIsActive())
                || !passwordEncoder.matches(request.getPassword(), agent.get().getPassword())) {
            return Optional.empty();
        }
        Agent a = agent.get();
        UserType role = a.getUserRole() != null ? a.getUserRole() : UserType.AGENT;
        log.info("Agent login succeeded: agentId={}, role={}", a.getId(), role);
        return Optional.of(LoginResponse.builder()
                .success(true)
                .message("Login successful")
                .userId(a.getId())
                .name(a.getName())
                .role(role.name())
                .tenantId(a.getTenantId())
                .token(jwtService.generateToken(a.getEmail(), role, a.getId(), a.getTenantId()))
                .build());
    }
}
