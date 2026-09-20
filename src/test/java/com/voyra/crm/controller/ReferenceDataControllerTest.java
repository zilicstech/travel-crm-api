package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every tenant role that builds a service (Owner, Agent, Accountant) must reach this - it is
 * deliberately broader than TaxConfigController's Owner/Accountant-only gate, because an AGENT
 * is exactly who needs an airport list while filling in a flight service.
 */
@WebMvcTest(ReferenceDataController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class ReferenceDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void airports_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/reference/airports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void airports_agentAllowed() throws Exception {
        mockMvc.perform(get("/api/reference/airports")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].iata").exists());
    }

    @Test
    void airports_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/reference/airports")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void airports_accountantAllowed() throws Exception {
        mockMvc.perform(get("/api/reference/airports")
                        .with(SecurityMockMvcRequestPostProcessors.user("acc1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());
    }

    @Test
    void airports_superAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/reference/airports")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void countries_agentAllowed() throws Exception {
        mockMvc.perform(get("/api/reference/countries")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists());
    }
}
