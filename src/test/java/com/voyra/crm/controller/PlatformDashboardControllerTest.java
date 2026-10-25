package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlatformDashboardResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformDashboardController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformDashboardControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformDashboardService service;

    @Test
    void superAdminGetsTheTiles() throws Exception {
        given(service.dashboard()).willReturn(PlatformDashboardResponse.builder().gmv30d(new BigDecimal("125000.00"))
                .walletFloat(new BigDecimal("3500.50")).unknownBookings(1).failedEmails(6).build());
        mockMvc.perform(get("/api/platform/dashboard").with(user("a").roles("SUPER_ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.gmv30d").value(125000.0))
                .andExpect(jsonPath("$.walletFloat").value(3500.5))
                .andExpect(jsonPath("$.unknownBookings").value(1))
                .andExpect(jsonPath("$.failedEmails").value(6));
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/dashboard").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
