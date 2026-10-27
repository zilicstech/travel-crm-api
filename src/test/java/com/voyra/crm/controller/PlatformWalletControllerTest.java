package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.WalletQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.voyra.crm.dto.WalletEntryResponse;
import com.voyra.crm.dto.WalletSummaryResponse;
import com.voyra.crm.exception.NotFoundException;

@WebMvcTest(PlatformWalletController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformWalletControllerTest {

    private static final String BASE = "/api/platform/wallets/T1";
    private static final String ADJUST = "{\"amount\":-25.00,\"reason\":\"Typo fix\",\"requestId\":\"req-12345678\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private WalletQueryService service;

    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "admin", UserType.SUPER_ADMIN, null), null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @Test
    void summaryAndMissingWallet() throws Exception {
        given(service.summary("T1")).willReturn(WalletSummaryResponse.builder().balance(BigDecimal.ONE).build());
        mockMvc.perform(get(BASE).with(admin())).andExpect(status().isOk());
        given(service.summary("T9")).willThrow(new NotFoundException("Wallet not found for this agency"));
        mockMvc.perform(get("/api/platform/wallets/T9").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void adjustPostsThroughTheService() throws Exception {
        given(service.adjust(eq("T1"), any())).willReturn(WalletEntryResponse.builder().id("E1").build());
        mockMvc.perform(post(BASE + "/adjust").with(admin()).contentType(MediaType.APPLICATION_JSON).content(ADJUST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("E1"));
        verify(service).adjust(eq("T1"), any());
    }

    @Test
    void adjustValidation() throws Exception {
        mockMvc.perform(post(BASE + "/adjust").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0,\"reason\":\"Typo fix\",\"requestId\":\"req-12345678\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.errors.amount").exists());
        mockMvc.perform(post(BASE + "/adjust").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5,\"reason\":\"Typo fix\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.errors.requestId").exists());
    }

    /** R15: an adjustment beyond 10,00,00,000 either way is a plain field error, never the raw digits wording. */
    @Test
    void anAdjustmentAboveTheCapIsAFieldError() throws Exception {
        for (String amount : new String[]{"99999999999", "-100000000.01"}) {
            mockMvc.perform(post(BASE + "/adjust").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":" + amount + ",\"reason\":\"Typo fix\",\"requestId\":\"req-12345678\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.errors.amount").value("An adjustment can be at most ₹10,00,00,000 either way"));
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void closeNeedsAUtr() throws Exception {
        mockMvc.perform(post(BASE + "/close").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        given(service.close("T1", "UTR12345")).willReturn(WalletEntryResponse.builder().id("E2").build());
        mockMvc.perform(post(BASE + "/close").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"utr\":\"UTR12345\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void testFlag() throws Exception {
        mockMvc.perform(put(BASE + "/test").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"isTest\":true}"))
                .andExpect(status().isOk());
        verify(service).setTest("T1", true);
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get(BASE).with(authentication(new UsernamePasswordAuthenticationToken(
                        new CustomUserPrincipal("U1", "o", UserType.AGENCY_OWNER, "T1"), null,
                        List.of(new SimpleGrantedAuthority("ROLE_AGENCY_OWNER"))))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/adjust").with(authentication(new UsernamePasswordAuthenticationToken(
                        new CustomUserPrincipal("U1", "o", UserType.AGENCY_OWNER, "T1"), null,
                        List.of(new SimpleGrantedAuthority("ROLE_AGENCY_OWNER"))))).contentType(MediaType.APPLICATION_JSON).content(ADJUST))
                .andExpect(status().isForbidden());
    }
}
