package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AuthEntitlementsControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AuthService authService;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "u", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void everyAgencyRoleReadsItsEntitlements() throws Exception {
        given(authService.currentEntitlements()).willReturn(new com.voyra.crm.dto.AgencyEntitlementsResponse(new Entitlements(
                SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL, Set.of(), 5, true, null), com.voyra.crm.enums.TenantStatus.SUSPENDED));
        for (UserType role : List.of(UserType.AGENCY_OWNER, UserType.AGENT, UserType.ACCOUNTANT)) {
            mockMvc.perform(get("/api/auth/entitlements").with(as(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("SUITE"))
                    .andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.seats").value(5))
                    .andExpect(jsonPath("$.agencyStatus").value("SUSPENDED")).andExpect(jsonPath("$.entitlements").doesNotExist());
        }
    }

    @Test
    void superAdminAndAnonymousCannot() throws Exception {
        mockMvc.perform(get("/api/auth/entitlements").with(as(UserType.SUPER_ADMIN))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/entitlements")).andExpect(status().isUnauthorized());
    }
}
