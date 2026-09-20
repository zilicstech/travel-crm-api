package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.SupplierInvoiceDraftRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PayablesDashboardService;
import com.voyra.crm.service.SupplierCreditNoteService;
import com.voyra.crm.service.SupplierInvoiceService;
import com.voyra.crm.service.SupplierLedgerService;
import com.voyra.crm.service.SupplierPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payables are accounts-payable data: Owner/Accountant only, never agent-scoped - mirrors
 * {@code CreditNoteControllerAuthTest}. Every route across all five payables controllers must
 * reject an AGENT with 403.
 */
@WebMvcTest({SupplierInvoiceController.class, SupplierPaymentController.class,
        SupplierLedgerController.class, SupplierCreditNoteController.class, PayablesDashboardController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PayablesAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupplierInvoiceService supplierInvoiceService;
    @MockBean
    private SupplierPaymentService supplierPaymentService;
    @MockBean
    private SupplierLedgerService supplierLedgerService;
    @MockBean
    private SupplierCreditNoteService supplierCreditNoteService;
    @MockBean
    private PayablesDashboardService payablesDashboardService;

    private SupplierInvoiceDraftRequest billRequest() {
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V1");
        r.setCategory(BookingType.FLIGHT);
        r.setInvoiceDate(LocalDate.now());
        return r;
    }

    private SupplierPaymentRequest paymentRequest() {
        SupplierPaymentRequest r = new SupplierPaymentRequest();
        r.setVendorId("V1");
        r.setAmount(java.math.BigDecimal.TEN);
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setPaidOn(LocalDate.now());
        return r;
    }

    @Test
    void agentCannotCreateASupplierBill() throws Exception {
        mockMvc.perform(post("/api/accounts/payables/invoices")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(billRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotListSupplierBills() throws Exception {
        mockMvc.perform(get("/api/accounts/payables/invoices")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotRecordAPayment() throws Exception {
        mockMvc.perform(post("/api/accounts/payables/payments")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotReadTheVendorLedger() throws Exception {
        mockMvc.perform(get("/api/accounts/payables/ledger/vendors/V1")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotListSupplierCreditNotes() throws Exception {
        mockMvc.perform(get("/api/accounts/payables/credit-notes").param("vendorId", "V1")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentCannotReadThePayablesDashboard() throws Exception {
        mockMvc.perform(get("/api/accounts/payables/dashboard/summary")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanCreateASupplierBill() throws Exception {
        when(supplierInvoiceService.createDraft(any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI1").build());
        mockMvc.perform(post("/api/accounts/payables/invoices")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(billRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void accountantCanRecordAPayment() throws Exception {
        when(supplierPaymentService.pay(any()))
                .thenReturn(com.voyra.crm.dto.SupplierPaymentResponse.builder().id("SP1").build());
        mockMvc.perform(post("/api/accounts/payables/payments")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest())))
                .andExpect(status().isOk());
    }
}
