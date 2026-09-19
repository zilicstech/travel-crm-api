package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.OpeningBalanceRequest;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.CustomerLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The customer ledger is Owner/Accountant only - an Agent never sees a client's financial position here. */
@WebMvcTest(CustomerLedgerController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class CustomerLedgerControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerLedgerService customerLedgerService;

    @Test
    void agentCannotReadAStatement() throws Exception {
        mockMvc.perform(get("/api/accounts/ledger/clients/K1")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotReadOutstanding() throws Exception {
        mockMvc.perform(get("/api/accounts/ledger/outstanding")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantCanReadAStatementAndPostAnOpeningBalance() throws Exception {
        when(customerLedgerService.statement(any(), any(), any()))
                .thenReturn(com.voyra.crm.dto.LedgerStatementResponse.builder().clientId("K1").build());
        mockMvc.perform(get("/api/accounts/ledger/clients/K1")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());

        OpeningBalanceRequest request = new OpeningBalanceRequest();
        request.setAmount(new BigDecimal("15000.00"));
        request.setAsOfDate(LocalDate.of(2026, 4, 1));
        when(customerLedgerService.openingBalance(any(), any()))
                .thenReturn(com.voyra.crm.dto.LedgerStatementResponse.builder().clientId("K1").build());

        mockMvc.perform(post("/api/accounts/ledger/clients/K1/opening-balance")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanReadOutstanding() throws Exception {
        when(customerLedgerService.outstanding()).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/accounts/ledger/outstanding")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }
}
