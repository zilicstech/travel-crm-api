package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.LeadTimelineService;
import com.voyra.crm.service.ProposalLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Leads are shared between AGENCY_OWNER and AGENT for every action - there is no owner-only
 * lead-level endpoint any more (assignment lives per-service on LeadServiceController). What
 * this asserts is the outer boundary: no anonymous access, no SUPER_ADMIN reaching into a
 * tenant's leads; who sees which leads is answered in the service (createdBy scoping).
 */
@WebMvcTest(LeadController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class LeadControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LeadService leadService;
    @MockBean
    private LeadTimelineService leadTimelineService;
    @MockBean
    private ProposalLinkService proposalLinkService;

    @Test
    void listLeads_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/leads"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listLeads_agentAllowed() throws Exception {
        mockMvc.perform(get("/api/leads")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void listLeads_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/leads")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    /** The platform admin manages agencies, never the business data inside one. */
    @Test
    void listLeads_superAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/leads")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }
}
