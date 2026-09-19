package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.InvoiceCancelRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.TaxPreviewRequest;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.InvoiceDocumentService;
import com.voyra.crm.service.TaxEngine;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tax preview is Owner/Accountant only - an Agent must never reach it, even though the class itself is ACCOUNTS_READ. */
@WebMvcTest(InvoiceDocumentController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class InvoiceDocumentControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaxEngine taxEngine;
    @MockBean
    private InvoiceDocumentService invoiceDocumentService;

    private TaxPreviewRequest request() {
        TaxPreviewRequest r = new TaxPreviewRequest();
        r.setClientId("K1");
        r.setTaxableAmount(new BigDecimal("1000"));
        r.setSupplyNature(SupplyNature.OTHER);
        return r;
    }

    private TaxComputationResult zeroResult() {
        return new TaxComputationResult(
                TaxTreatment.INTRA_STATE, "27", "9985", new BigDecimal("1000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"));
    }

    @Test
    void agentForbidden() throws Exception {
        mockMvc.perform(post("/api/accounts/invoices/preview-tax")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantAllowed() throws Exception {
        when(taxEngine.compute(any())).thenReturn(zeroResult());

        mockMvc.perform(post("/api/accounts/invoices/preview-tax")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    void ownerAllowed() throws Exception {
        when(taxEngine.compute(any())).thenReturn(zeroResult());

        mockMvc.perform(post("/api/accounts/invoices/preview-tax")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
    }

    @Test
    void agentCannotCreateADraft() throws Exception {
        InvoiceDraftRequest draft = new InvoiceDraftRequest();
        draft.setBookingId("B1");
        draft.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        mockMvc.perform(post("/api/accounts/invoices")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draft)))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCanListButNotIssueOrCancel() throws Exception {
        when(invoiceDocumentService.list(any(), any(), any(), any(), any(), any())).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/accounts/invoices")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/accounts/invoices/I1/issue")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());

        InvoiceCancelRequest cancelRequest = new InvoiceCancelRequest();
        cancelRequest.setReason("test");
        mockMvc.perform(post("/api/accounts/invoices/I1/cancel")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotIssueProformaOrConvertIt() throws Exception {
        mockMvc.perform(post("/api/accounts/invoices/I1/issue-proforma")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/accounts/invoices/I1/convert-to-tax-invoice")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCanReadThePdfOfTheirOwnInvoice() throws Exception {
        when(invoiceDocumentService.get("I1")).thenReturn(
                com.voyra.crm.dto.InvoiceResponse.builder().id("I1").invoiceNumber("INV/2026-27/0001").build());
        when(invoiceDocumentService.getPdf("I1")).thenReturn("%PDF-1.5 stub".getBytes());

        mockMvc.perform(get("/api/accounts/invoices/I1/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF));
    }
}
