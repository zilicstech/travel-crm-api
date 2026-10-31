package com.voyra.crm.service;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * An ACCOUNTANT resolves through agentRepository exactly like an AGENT does - both are
 * "staff users" stored in the agent table. Before CustomUserPrincipal.isStaffUser() existed,
 * an accountant fell through to the tenant-lookup branch and threw IllegalStateException.
 */
@ExtendWith(MockitoExtension.class)
class AuthorResolverTest {

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private AuthorResolver authorResolver;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesAnAccountantThroughTheAgentTable() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha@example.com", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        Agent accountant = Agent.builder().id("AC1").name("Neha Kapoor").userRole(UserType.ACCOUNTANT).build();
        when(agentRepository.findById("AC1")).thenReturn(Optional.of(accountant));

        AuthorResolver.AuthorInfo info = authorResolver.resolveCurrentAuthor();

        assertThat(info.id()).isEqualTo("AC1");
        assertThat(info.name()).isEqualTo("Neha Kapoor");
    }

    @Test
    void resolvesAnOwnerThroughTheTenantTable() {
        CustomUserPrincipal principal = new CustomUserPrincipal("T1", "owner@example.com", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        Tenant tenant = Tenant.builder().id("T1").ownerName("John Davis").build();
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(tenant));

        AuthorResolver.AuthorInfo info = authorResolver.resolveCurrentAuthor();

        assertThat(info.id()).isEqualTo("T1");
        assertThat(info.name()).isEqualTo("John Davis");
    }

    /** Platform-driven tenant writes are authored by Voyra itself, whoever (if anyone) is signed in on the thread. */
    @Test
    void theSystemActorIsVoyraAndOnlyInsideItsScope() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("ROOT", "root@voyra.test", UserType.SUPER_ADMIN, null), null, List.of()));

        AuthorResolver.AuthorInfo inside = com.voyra.crm.security.SystemActor.call("T1", authorResolver::resolveCurrentAuthor);

        assertThat(inside.id()).isEqualTo("system");
        assertThat(inside.name()).isEqualTo("Voyra Platform");
        assertThat(com.voyra.crm.security.SecurityContextUtil.getCurrentUserOrThrow().userId()).isEqualTo("ROOT");
        assertThat(com.voyra.crm.security.SystemActor.isActive()).isFalse();
    }

    @Test
    void aStaffLoginOrOwnerWithoutItsRowFails() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.voyra.crm.security.CustomUserPrincipal("A9", "a", com.voyra.crm.enums.UserType.AGENT, "T1"), null, java.util.List.of()));
        org.mockito.Mockito.when(agentRepository.findById("A9")).thenReturn(java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authorResolver.resolveCurrentAuthor())
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A9");

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.voyra.crm.security.CustomUserPrincipal("T9", "o", com.voyra.crm.enums.UserType.AGENCY_OWNER, "T9"), null, java.util.List.of()));
        org.mockito.Mockito.when(tenantRepository.findById("T9")).thenReturn(java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authorResolver.resolveCurrentAuthor())
                .isInstanceOf(IllegalStateException.class).hasMessage("Agency not found: T9");
    }
}
