package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.SupplierInvoiceCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceStatusUpdateRequest;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Supplier invoices are accounts-payable data: owner-only, per the method-level @PreAuthorize override. */
@WebMvcTest(InvoiceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
class SupplierInvoiceAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InvoiceService invoiceService;

    private SupplierInvoiceCreateRequest createRequest() {
        SupplierInvoiceCreateRequest request = new SupplierInvoiceCreateRequest();
        request.setSupplierName("Cleartrip");
        request.setCategory(BookingType.HOTEL);
        request.setAmount(new BigDecimal("18000"));
        return request;
    }

    @Test
    void createSupplierInvoice_agentForbidden_ownerAllowed() throws Exception {
        mockMvc.perform(post("/api/invoices/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/invoices/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void listSupplierInvoices_agentForbidden_ownerAllowed() throws Exception {
        mockMvc.perform(get("/api/invoices/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/invoices/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void updateSupplierInvoiceStatus_agentForbidden_ownerAllowed() throws Exception {
        SupplierInvoiceStatusUpdateRequest request = new SupplierInvoiceStatusUpdateRequest();
        request.setStatus(InvoiceStatus.PAID);

        mockMvc.perform(patch("/api/invoices/supplier/W8X4Y1/status")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/invoices/supplier/W8X4Y1/status")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
