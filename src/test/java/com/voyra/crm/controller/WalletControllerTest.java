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

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.WalletSummaryResponse;

@WebMvcTest(WalletController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private WalletQueryService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "u", type, "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void ownerAndAccountantReadTheirOwnTenantWallet() throws Exception {
        given(service.summary("T1")).willReturn(WalletSummaryResponse.builder().balance(BigDecimal.TEN).build());
        mockMvc.perform(get("/api/wallet").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(10));
        mockMvc.perform(get("/api/wallet").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
    }

    @Test
    void agentIsForbidden() throws Exception {
        mockMvc.perform(get("/api/wallet").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/wallet/entries.csv").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void entriesArePaged() throws Exception {
        given(service.entries(eq("T1"), any())).willReturn(PagedResponse.<com.voyra.crm.dto.WalletEntryResponse>builder()
                .content(List.of()).page(0).size(25).build());
        mockMvc.perform(get("/api/wallet/entries?page=0&size=25").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(25));
    }

    @Test
    void csvIsTextCsvWithTheHeaderLine() throws Exception {
        given(service.csv("T1")).willReturn("Date,Type,Amount,Balance,Held,Available,Narration,By\n");
        mockMvc.perform(get("/api/wallet/entries.csv").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string("Date,Type,Amount,Balance,Held,Available,Narration,By\n"));
    }

    @Test
    void onlyTheOwnerSetsTheThreshold() throws Exception {
        String body = "{\"lowBalanceThreshold\":5000}";
        mockMvc.perform(put("/api/wallet/threshold").with(as(UserType.ACCOUNTANT)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/wallet/threshold").with(as(UserType.AGENCY_OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service).setThreshold("T1", new BigDecimal("5000"));
        mockMvc.perform(put("/api/wallet/threshold").with(as(UserType.AGENCY_OWNER)).contentType(MediaType.APPLICATION_JSON).content("{\"lowBalanceThreshold\":-1}"))
                .andExpect(status().isBadRequest());
    }
}
