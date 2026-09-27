package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The legacy client-invoice surface is read-only history now that the Accounts module is the
 * only place a customer invoice is raised (see InvoiceController's own javadoc) - create and
 * record-payment no longer exist here at all. Reads stay open to all three roles.
 */
@WebMvcTest(InvoiceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class InvoiceControllerAccountantAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvoiceService invoiceService;

    @Test
    void createClientInvoiceNoLongerExists() throws Exception {
        // /api/invoices/client is still a real route (GET) - POSTing to it is a wrong verb on
        // a live route (405), not a missing route (404).
        mockMvc.perform(post("/api/invoices/client")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void recordPaymentNoLongerExists() throws Exception {
        mockMvc.perform(patch("/api/invoices/client/CI1/payment")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyRoleCanListAndReadTheSummary() throws Exception {
        when(invoiceService.listClientInvoices()).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/invoices/client")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());

        when(invoiceService.getSummary()).thenReturn(com.voyra.crm.dto.InvoiceSummaryResponse.builder().build());
        mockMvc.perform(get("/api/invoices/summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }
}
