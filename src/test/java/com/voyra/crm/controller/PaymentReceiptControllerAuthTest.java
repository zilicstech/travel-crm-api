package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptReverseRequest;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PaymentReceiptService;
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

/** Recording and reversing are Owner/Accountant only; an Agent may only read, scoped server-side to their own invoices. */
@WebMvcTest(PaymentReceiptController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PaymentReceiptControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentReceiptService paymentReceiptService;

    private PaymentReceiptRequest request() {
        PaymentReceiptRequest r = new PaymentReceiptRequest();
        r.setInvoiceId("I1");
        r.setAmount(new BigDecimal("1000"));
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setReceivedOn(LocalDate.of(2026, 9, 19));
        return r;
    }

    @Test
    void agentCannotRecordAReceipt() throws Exception {
        mockMvc.perform(post("/api/accounts/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotReverseAReceipt() throws Exception {
        PaymentReceiptReverseRequest reverse = new PaymentReceiptReverseRequest();
        reverse.setReason("test");

        mockMvc.perform(post("/api/accounts/receipts/R1/reverse")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reverse)))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCanList() throws Exception {
        when(paymentReceiptService.list(any(), any(), any())).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/accounts/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void accountantCanRecordAReceipt() throws Exception {
        when(paymentReceiptService.record(any())).thenReturn(com.voyra.crm.dto.PaymentReceiptResponse.builder().id("R1").build());

        mockMvc.perform(post("/api/accounts/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanRecordAReceipt() throws Exception {
        when(paymentReceiptService.record(any())).thenReturn(com.voyra.crm.dto.PaymentReceiptResponse.builder().id("R1").build());

        mockMvc.perform(post("/api/accounts/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    void agentCanReadTheVoucherPdfOfTheirOwnReceipt() throws Exception {
        when(paymentReceiptService.get("R1")).thenReturn(com.voyra.crm.dto.PaymentReceiptResponse.builder().id("R1").receiptNumber("RCP/2026-27/0001").build());
        when(paymentReceiptService.getPdf("R1")).thenReturn("%PDF-1.5 stub".getBytes());

        mockMvc.perform(get("/api/accounts/receipts/R1/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF));
    }
}
