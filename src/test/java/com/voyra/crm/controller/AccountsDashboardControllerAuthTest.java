package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AccountsDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Accounts-wide aggregates are Owner/Accountant only - agency-wide by nature, never agent-scoped. */
@WebMvcTest(AccountsDashboardController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AccountsDashboardControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountsDashboardService accountsDashboardService;

    @Test
    void agentCannotReadTheSummary() throws Exception {
        mockMvc.perform(get("/api/accounts/dashboard/summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotExportTheGstRegister() throws Exception {
        mockMvc.perform(get("/api/accounts/dashboard/export/gst")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantCanReadTheSummary() throws Exception {
        when(accountsDashboardService.summary()).thenReturn(
                com.voyra.crm.dto.AccountsDashboardSummaryResponse.builder().build());
        mockMvc.perform(get("/api/accounts/dashboard/summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanReadTheGstSummary() throws Exception {
        when(accountsDashboardService.gstSummary(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/accounts/dashboard/gst-summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }
}
