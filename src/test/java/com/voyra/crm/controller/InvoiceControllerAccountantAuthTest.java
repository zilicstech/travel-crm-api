package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.InvoiceService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Epic 4: an Agent lost create/record-payment on the legacy client-invoice surface; reads stay open to all three roles. */
@WebMvcTest(InvoiceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class InvoiceControllerAccountantAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InvoiceService invoiceService;

    private ClientInvoiceCreateRequest createRequest() {
        ClientInvoiceCreateRequest r = new ClientInvoiceCreateRequest();
        r.setClientId("K1");
        r.setAmount(new BigDecimal("10000.00"));
        return r;
    }

    @Test
    void agentCannotCreateAClientInvoice() throws Exception {
        mockMvc.perform(post("/api/invoices/client")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantCanCreateAClientInvoice() throws Exception {
        mockMvc.perform(post("/api/invoices/client")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void agentCannotRecordAPaymentButCanStillListAndReadTheSummary() throws Exception {
        ClientInvoicePaymentRequest payment = new ClientInvoicePaymentRequest();
        payment.setAmountPaid(new BigDecimal("5000.00"));

        mockMvc.perform(patch("/api/invoices/client/CI1/payment")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payment)))
                .andExpect(status().isForbidden());

        when(invoiceService.listClientInvoices()).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/invoices/client")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());

        when(invoiceService.getSummary()).thenReturn(com.voyra.crm.dto.InvoiceSummaryResponse.builder().build());
        mockMvc.perform(get("/api/invoices/summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void accountantCanRecordAPayment() throws Exception {
        ClientInvoicePaymentRequest payment = new ClientInvoicePaymentRequest();
        payment.setAmountPaid(new BigDecimal("5000.00"));
        when(invoiceService.recordPayment(any(), any())).thenReturn(com.voyra.crm.dto.ClientInvoiceResponse.builder().build());

        mockMvc.perform(patch("/api/invoices/client/CI1/payment")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payment)))
                .andExpect(status().isOk());
    }
}
