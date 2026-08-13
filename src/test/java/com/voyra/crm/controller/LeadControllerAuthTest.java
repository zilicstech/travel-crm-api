package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.LeadAssignRequest;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.ProposalLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only the Owner may reassign a lead to a different agent (blueprint §5.2, method-level @PreAuthorize override). */
@WebMvcTest(LeadController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
class LeadControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LeadService leadService;
    @MockBean
    private ProposalLinkService proposalLinkService;

    @Test
    void assignAgent_agentForbidden() throws Exception {
        LeadAssignRequest request = new LeadAssignRequest();
        request.setAgentId("A1");

        mockMvc.perform(patch("/api/leads/L1/assign")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignAgent_ownerAllowed() throws Exception {
        LeadAssignRequest request = new LeadAssignRequest();
        request.setAgentId("A1");

        mockMvc.perform(patch("/api/leads/L1/assign")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    /**
     * Spring Security's AnonymousAuthenticationToken reports isAuthenticated()=true, so the
     * URL-level .anyRequest().authenticated() check passes for an anonymous caller; it is
     * @PreAuthorize's role check that then fails, throwing AccessDeniedException, which
     * GlobalExceptionHandler maps to 403 - not the 401 an unauthenticated request might suggest.
     */
    @Test
    void listLeads_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/leads"))
                .andExpect(status().isForbidden());
    }
}
