package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.SupplierCredentialUpsertRequest;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.SupplierCredentialService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Supplier credentials are owner-only on every route, including /reveal - an agent must never reach them. */
@WebMvcTest(SupplierCredentialController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class SupplierCredentialControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupplierCredentialService supplierCredentialService;

    private SupplierCredentialUpsertRequest upsertRequest() {
        SupplierCredentialUpsertRequest request = new SupplierCredentialUpsertRequest();
        request.setProvider(SupplierProvider.TRIPJACK);
        request.setEnvironment(SupplierEnvironment.UAT);
        request.setBaseUrl("https://apitest.tripjack.com");
        request.setApiKey("tj_test_key");
        return request;
    }

    @Test
    void upsert_agentForbidden_ownerAllowed() throws Exception {
        mockMvc.perform(put("/api/supplier-credentials")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest())))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/supplier-credentials")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void get_agentForbidden_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void reveal_agentForbidden_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK/reveal")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK/reveal")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void reveal_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK/reveal"))
                .andExpect(status().isUnauthorized());
    }
}
