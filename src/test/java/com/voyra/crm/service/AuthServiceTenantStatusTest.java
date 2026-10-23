package com.voyra.crm.service;

import com.voyra.crm.dto.LoginRequest;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * tenant.status is the liveness truth at sign-in: SUSPENDED still signs in (writes are blocked
 * later, by TenantStatusInterceptor), DEACTIVATED is refused with the same generic failure as a
 * wrong password - for the owner and for every agent of that agency.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTenantStatusTest {

    private static final String GENERIC_FAILURE = "Invalid email or password";

    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private BaseCurrency baseCurrency;
    @InjectMocks private AuthService authService;

    @BeforeEach
    void passwordsMatch() {
        lenient().when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
    }

    @Test
    void ownerOfASuspendedAgencyCanLogIn() {
        ownerWithStatus(TenantStatus.SUSPENDED);
        assertThat(authService.loginOwner(credentials("owner@a.com")).isSuccess()).isTrue();
    }

    @Test
    void ownerOfADeactivatedAgencyIsRefused() {
        ownerWithStatus(TenantStatus.DEACTIVATED);
        LoginResponse response = authService.loginOwner(credentials("owner@a.com"));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(GENERIC_FAILURE);
    }

    @Test
    void agentOfADeactivatedAgencyIsRefused() {
        agentInTenantWithStatus(TenantStatus.DEACTIVATED);
        LoginResponse response = authService.loginAgent(credentials("agent@a.com"));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(GENERIC_FAILURE);
    }

    @Test
    void agentOfASuspendedAgencyCanLogIn() {
        agentInTenantWithStatus(TenantStatus.SUSPENDED);
        assertThat(authService.loginAgent(credentials("agent@a.com")).isSuccess()).isTrue();
    }

    /** Built with the builder, so isActive keeps its default true - only status can refuse. */
    private void ownerWithStatus(TenantStatus status) {
        Tenant tenant = Tenant.builder().id("T1").ownerEmail("owner@a.com").ownerName("Owner")
                .password("enc").status(status).build();
        when(tenantRepository.findByOwnerEmailIgnoreCase("owner@a.com")).thenReturn(Optional.of(tenant));
    }

    private void agentInTenantWithStatus(TenantStatus status) {
        Agent agent = Agent.builder().id("A1").tenantId("T1").email("agent@a.com").name("Agent")
                .password("enc").isActive(true).build();
        when(agentRepository.findByEmailIgnoreCase("agent@a.com")).thenReturn(Optional.of(agent));
        lenient().when(tenantRepository.findById("T1"))
                .thenReturn(Optional.of(Tenant.builder().id("T1").status(status).build()));
    }

    private static LoginRequest credentials(String email) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword("secret");
        return request;
    }
}
