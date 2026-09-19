package com.voyra.crm.security;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.enums.UserType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * isPrincipalActive() is an exhaustive switch over UserType with no default case - adding
 * ACCOUNTANT to the enum forced a case here (JwtAuthenticationFilter.java). This pins that
 * an accountant's liveness is checked against AgentCache, same as an AGENT, and that a
 * deactivated accountant's still-valid JWT is rejected immediately.
 */
class JwtAuthenticationFilterAccountantTest {

    private final JwtService jwtService = new JwtService();
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "jwtSecret", "test-only-jwt-secret-key-at-least-256-bits-long-for-hs256-signing");
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 86_400_000L);
        TenantCache.put("T1", "Agency", true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AgentCache.remove("AC1");
    }

    @Test
    void activeAccountantAuthenticatesWithRoleAccountant() throws Exception {
        AgentCache.put("AC1", true);
        String token = jwtService.generateToken("neha@example.com", UserType.ACCOUNTANT, "AC1", "T1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((CustomUserPrincipal) auth.getPrincipal()).isAccountant()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void deactivatedAccountantIsRejectedDespiteAValidToken() throws Exception {
        AgentCache.put("AC1", false);
        String token = jwtService.generateToken("neha@example.com", UserType.ACCOUNTANT, "AC1", "T1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
