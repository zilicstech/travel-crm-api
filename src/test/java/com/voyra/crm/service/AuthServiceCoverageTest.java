package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.AgencyEntitlementsResponse;
import com.voyra.crm.dto.LoginRequest;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceCoverageTest {

    private static final String TENANT = "T-AUTH-COV";

    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private BaseCurrency baseCurrency;
    @Mock private EntitlementService entitlementService;
    @InjectMocks private AuthService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantCache.remove(TENANT);
    }

    private static LoginRequest credentials(String email) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword("pw");
        return r;
    }

    private static Entitlements suite() {
        return new Entitlements(SubscriptionPlan.SUITE, null, EnumSet.noneOf(com.voyra.crm.enums.Sku.class), 5, false, null);
    }

    @Test
    void aSignedInUserReadsTheirAgencysEntitlementsAndStatus() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "u", UserType.AGENT, TENANT), null, List.of()));
        when(entitlementService.resolve(TENANT)).thenReturn(suite());
        TenantCache.put(TENANT, "Agency", TenantStatus.SUSPENDED);

        AgencyEntitlementsResponse r = service.currentEntitlements();

        assertThat(r.getEntitlements().plan()).isEqualTo(SubscriptionPlan.SUITE);
        assertThat(r.getAgencyStatus()).isEqualTo(TenantStatus.SUSPENDED);

        TenantCache.remove(TENANT);
        assertThat(service.currentEntitlements().getAgencyStatus()).isNull();
    }

    @Test
    void aPlatformAdminHasNoAgencyEntitlements() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("P1", "u", UserType.SUPER_ADMIN, null), null, List.of()));
        assertThatThrownBy(() -> service.currentEntitlements())
                .isInstanceOf(AccessDeniedException.class).hasMessage("No agency for the current user");
        verifyNoInteractions(entitlementService);
    }

    @Test
    void anActivePlatformAdminSignsInFirstWithNoTenant() {
        when(platformAdminRepository.findByEmailIgnoreCase("admin@example.test")).thenReturn(Optional.of(
                PlatformAdmin.builder().id("P1").name("Root").email("admin@example.test").password("enc").isActive(true).build()));
        when(passwordEncoder.matches("pw", "enc")).thenReturn(true);
        when(jwtService.generateToken("admin@example.test", UserType.SUPER_ADMIN, "P1", null)).thenReturn("jwt-admin");

        LoginResponse r = service.login(credentials("admin@example.test"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getRole()).isEqualTo("SUPER_ADMIN");
        assertThat(r.getToken()).isEqualTo("jwt-admin");
        assertThat(r.getTenantId()).isNull();
        verifyNoInteractions(tenantRepository, agentRepository);
        assertThat(service.loginPlatformAdmin(credentials("admin@example.test")).getUserId()).isEqualTo("P1");
    }

    @Test
    void anInactiveOrWrongPasswordAdminFallsThroughToTheOwner() {
        when(platformAdminRepository.findByEmailIgnoreCase("x@example.test")).thenReturn(
                Optional.of(PlatformAdmin.builder().id("P1").password("enc").isActive(false).build()),
                Optional.of(PlatformAdmin.builder().id("P1").password("enc").isActive(true).build()));
        when(passwordEncoder.matches("pw", "enc")).thenReturn(false);
        when(tenantRepository.findByOwnerEmailIgnoreCase("x@example.test")).thenReturn(Optional.of(Tenant.builder().id(TENANT)
                .ownerName("Dana").ownerEmail("x@example.test").password("owner-enc").status(TenantStatus.ACTIVE).build()));
        when(passwordEncoder.matches("pw", "owner-enc")).thenReturn(true);
        when(baseCurrency.of(TENANT)).thenReturn("AED");
        when(entitlementService.resolve(TENANT)).thenReturn(suite());
        when(jwtService.generateToken("x@example.test", UserType.AGENCY_OWNER, TENANT, TENANT)).thenReturn("jwt-owner");

        LoginResponse inactive = service.login(credentials("x@example.test"));
        LoginResponse wrongPassword = service.login(credentials("x@example.test"));

        assertThat(inactive.getRole()).isEqualTo("AGENCY_OWNER");
        assertThat(inactive.getBaseCurrencyCode()).isEqualTo("AED");
        assertThat(inactive.getEntitlements().getAgencyStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(wrongPassword.getToken()).isEqualTo("jwt-owner");
        verifyNoInteractions(agentRepository);
    }

    @Test
    void anOwnerWithAWrongPasswordFallsThroughToTheAgentStore() {
        when(tenantRepository.findByOwnerEmailIgnoreCase("a@example.test")).thenReturn(Optional.of(Tenant.builder().id(TENANT)
                .password("owner-enc").status(TenantStatus.ACTIVE).build()));
        when(passwordEncoder.matches("pw", "owner-enc")).thenReturn(false);
        when(agentRepository.findByEmailIgnoreCase("a@example.test")).thenReturn(Optional.of(Agent.builder().id("A1").tenantId(TENANT)
                .name("Neha").email("a@example.test").password("agent-enc").isActive(true).userRole(UserType.ACCOUNTANT).build()));
        when(passwordEncoder.matches("pw", "agent-enc")).thenReturn(true);
        when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(Tenant.builder().id(TENANT).status(TenantStatus.ACTIVE).build()));
        when(baseCurrency.of(TENANT)).thenReturn("INR");
        when(entitlementService.resolve(TENANT)).thenReturn(suite());
        when(jwtService.generateToken("a@example.test", UserType.ACCOUNTANT, "A1", TENANT)).thenReturn("jwt-acct");

        LoginResponse r = service.login(credentials("a@example.test"));

        assertThat(r.getRole()).isEqualTo("ACCOUNTANT");
        assertThat(r.getName()).isEqualTo("Neha");
        assertThat(r.getTenantId()).isEqualTo(TENANT);
        assertThat(r.getToken()).isEqualTo("jwt-acct");
    }

    @Test
    void anAgentWithoutARoleSignsInAsAnAgent() {
        when(agentRepository.findByEmailIgnoreCase("a@example.test")).thenReturn(Optional.of(Agent.builder().id("A1").tenantId(TENANT)
                .email("a@example.test").password("agent-enc").isActive(true).userRole(null).build()));
        when(passwordEncoder.matches("pw", "agent-enc")).thenReturn(true);
        when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(Tenant.builder().id(TENANT).status(TenantStatus.ACTIVE).build()));
        when(entitlementService.resolve(TENANT)).thenReturn(suite());
        when(jwtService.generateToken("a@example.test", UserType.AGENT, "A1", TENANT)).thenReturn("jwt-agent");

        assertThat(service.loginAgent(credentials("a@example.test")).getRole()).isEqualTo("AGENT");
    }

    @Test
    void nobodyMatchingGetsTheGenericFailureEverywhere() {
        LoginResponse r = service.login(credentials("nobody@example.test"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Invalid email or password");
        assertThat(service.loginPlatformAdmin(credentials("nobody@example.test")).getMessage()).isEqualTo("Invalid email or password");
        verify(jwtService, never()).generateToken(any(), any(), any(), any());
    }

    @Test
    void anInactiveAgentAWrongPasswordOrAnAgentWithoutAnAgencyIsRefused() {
        when(agentRepository.findByEmailIgnoreCase("a@example.test")).thenReturn(
                Optional.of(Agent.builder().id("A1").password("enc").isActive(false).build()),
                Optional.of(Agent.builder().id("A1").password("enc").isActive(true).build()),
                Optional.of(Agent.builder().id("A1").password("enc").isActive(true).tenantId(null).build()));
        when(passwordEncoder.matches("pw", "enc")).thenReturn(false, true);

        for (int i = 0; i < 3; i++) {
            assertThat(service.loginAgent(credentials("a@example.test")).isSuccess()).isFalse();
        }
        verifyNoInteractions(tenantRepository, jwtService);
    }
}
