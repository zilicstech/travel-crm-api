package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.ClientService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clients are shared between AGENCY_OWNER and AGENT, so the role gate lets both in and the
 * ownership question is answered in the service. What this asserts is the outer boundary: no
 * anonymous access, no SUPER_ADMIN reaching into a tenant's client list.
 */
@WebMvcTest(ClientController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class ClientControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ClientService clientService;

    @Test
    void listClients_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listClients_agentAllowed() throws Exception {
        mockMvc.perform(get("/api/clients")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void listClients_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/clients")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    /** The platform admin manages agencies, never the business data inside one. */
    @Test
    void listClients_superAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/clients")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createClient_missingRequiredFieldsIsRejectedAsBadRequest() throws Exception {
        ClientCreateRequest request = new ClientCreateRequest();
        request.setType(ClientType.B2C);

        mockMvc.perform(post("/api/clients")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
