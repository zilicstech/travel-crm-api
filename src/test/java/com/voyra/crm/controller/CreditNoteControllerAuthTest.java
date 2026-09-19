package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.CreditNoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Credit notes are Owner/Accountant only - unlike invoices and receipts, an Agent never sees these at all. */
@WebMvcTest(CreditNoteController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class CreditNoteControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CreditNoteService creditNoteService;

    private CreditNoteRequest request() {
        CreditNoteRequest r = new CreditNoteRequest();
        r.setInvoiceId("I1");
        r.setReason(CreditNoteReason.BOOKING_CANCELLED);
        return r;
    }

    @Test
    void agentCannotCreateACreditNote() throws Exception {
        mockMvc.perform(post("/api/accounts/credit-notes")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotListCreditNotes() throws Exception {
        mockMvc.perform(get("/api/accounts/credit-notes")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantCanCreateAndIssueACreditNote() throws Exception {
        when(creditNoteService.create(any()))
                .thenReturn(com.voyra.crm.dto.CreditNoteResponse.builder().id("CN1").build());
        mockMvc.perform(post("/api/accounts/credit-notes")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());

        when(creditNoteService.issue("CN1"))
                .thenReturn(com.voyra.crm.dto.CreditNoteResponse.builder().id("CN1").build());
        mockMvc.perform(post("/api/accounts/credit-notes/CN1/issue")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanListCreditNotes() throws Exception {
        when(creditNoteService.list(any(), any(), any())).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/accounts/credit-notes")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }
}
