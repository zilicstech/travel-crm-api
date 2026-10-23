package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.service.PlatformSupplierTestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Super admin only; the old per-agency route is gone; no response ever carries the plaintext key. */
@WebMvcTest(PlatformSupplierCredentialController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformSupplierCredentialControllerTest {

    private static final String BODY = "{\"provider\":\"TRIPJACK\",\"environment\":\"UAT\","
            + "\"baseUrl\":\"https://apitest.tripjack.com\",\"apiKey\":\"tj_live_key_9f3a\",\"isActive\":true}";

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformSupplierCredentialService credentialService;
    @MockBean private PlatformSupplierTestService testService;

    @Test
    void superAdminCanSetTheCredential() throws Exception {
        when(credentialService.upsert(any())).thenReturn(PlatformSupplierCredentialResponse.builder()
                .id("C1").provider(SupplierProvider.TRIPJACK).maskedApiKey("••••••9f3a").isActive(true).build());

        mockMvc.perform(put("/api/platform/supplier-credentials").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedApiKey").value("••••••9f3a"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void agencyOwnerCannotSetTheCredential() throws Exception {
        mockMvc.perform(put("/api/platform/supplier-credentials").with(user("owner").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(credentialService);
    }

    @Test
    void missingBaseUrlIsABadRequest() throws Exception {
        mockMvc.perform(put("/api/platform/supplier-credentials").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TRIPJACK\",\"environment\":\"UAT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void superAdminCanRunTheTestCall() throws Exception {
        when(testService.testCall(SupplierProvider.TRIPJACK)).thenReturn(new SupplierTestResult(true, "ok", 412));

        mockMvc.perform(post("/api/platform/supplier-credentials/TRIPJACK/test").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.millis").value(412));
    }

    @Test
    void theOldAgencyRouteIsGone() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName("com.voyra.crm.controller.SupplierCredentialController"))
                .isInstanceOf(ClassNotFoundException.class);
        mockMvc.perform(get("/api/supplier-credentials/TRIPJACK").with(user("owner").roles("AGENCY_OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoRevealRoute() throws Exception {
        mockMvc.perform(get("/api/platform/supplier-credentials/TRIPJACK/reveal").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
