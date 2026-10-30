package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.SupplierProductTerms;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.PricingProduct;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Super admin only; the old per-agency and upsert routes are gone; no response ever carries the plaintext key. */
@WebMvcTest(PlatformSupplierCredentialController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformSupplierCredentialControllerTest {

    private static final String BASE = "/api/platform/supplier-credentials";
    private static final String BODY = "{\"name\":\"Tripjack\",\"provider\":\"TRIPJACK\",\"apiKey\":\"tj_live_key_9f3a\","
            + "\"isActive\":true,\"products\":[{\"product\":\"FLIGHT\",\"commissionEstimatePct\":5}]}";

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformSupplierCredentialService credentialService;
    @MockBean private PlatformSupplierTestService testService;

    private static PlatformSupplierCredentialResponse response() {
        return PlatformSupplierCredentialResponse.builder().id("C1").name("Tripjack").provider(SupplierProvider.TRIPJACK)
                .maskedApiKey("••••••9f3a").isActive(true)
                .products(List.of(new SupplierProductTerms(PricingProduct.FLIGHT, new BigDecimal("5")))).build();
    }

    @Test
    void superAdminCanAddASupplier() throws Exception {
        when(credentialService.create(any())).thenReturn(response());

        mockMvc.perform(post(BASE).with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedApiKey").value("••••••9f3a"))
                .andExpect(jsonPath("$.products[0].commissionEstimatePct").value(5))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.baseUrl").doesNotExist());
    }

    @Test
    void superAdminCanListEditAndChangeTerms() throws Exception {
        when(credentialService.list()).thenReturn(List.of(response()));
        when(credentialService.update(eq("C1"), any())).thenReturn(response());
        when(credentialService.updateProducts(eq("C1"), any())).thenReturn(response());

        mockMvc.perform(get(BASE).with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Tripjack"));
        mockMvc.perform(put(BASE + "/C1").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tripjack\",\"isActive\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/C1/products").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"product\":\"HOTEL\",\"commissionEstimatePct\":6}]"))
                .andExpect(status().isOk());
    }

    @Test
    void agencyOwnerCannotAddASupplier() throws Exception {
        mockMvc.perform(post(BASE).with(user("owner").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(credentialService);
    }

    /** R13: /api/platform/** is SUPER_ADMIN at the filter chain, so a non-admin's invalid body is 403, not 400 field errors. */
    @Test
    void anOwnersInvalidBodyIsForbiddenBeforeValidation() throws Exception {
        for (String role : new String[]{"AGENCY_OWNER", "AGENT", "ACCOUNTANT"}) {
            mockMvc.perform(post(BASE).with(user("u").roles(role))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
        verifyNoInteractions(credentialService);
    }

    @Test
    void missingNameOrKeyIsABadRequest() throws Exception {
        mockMvc.perform(post(BASE).with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TRIPJACK\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(credentialService);
    }

    @Test
    void anEnvironmentOrBaseUrlInTheBodyIsIgnored() throws Exception {
        when(credentialService.create(any())).thenReturn(response());
        mockMvc.perform(post(BASE).with(user("root").roles("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("{\"name\"", "{\"environment\":\"UAT\",\"baseUrl\":\"https://x.example\",\"name\"")))
                .andExpect(status().isCreated());
    }

    @Test
    void superAdminCanTestOneSupplier() throws Exception {
        when(testService.testCall("C1")).thenReturn(new SupplierTestResult(true, "ok", 412));

        mockMvc.perform(post(BASE + "/C1/test").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.millis").value(412));
    }

    @Test
    void theOldUpsertRouteIsGone() throws Exception {
        mockMvc.perform(put(BASE).with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isMethodNotAllowed());
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
        mockMvc.perform(get(BASE + "/C1/reveal").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
