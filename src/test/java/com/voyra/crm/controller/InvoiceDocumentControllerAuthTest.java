package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.TaxPreviewRequest;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
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

    private TaxPreviewRequest request() {
        TaxPreviewRequest r = new TaxPreviewRequest();
        r.setClientId("K1");
        r.setTaxableAmount(new BigDecimal("1000"));
        r.setSupplyNature(SupplyNature.OTHER);
        return r;
    }

    private TaxComputationResult zeroResult() {
        return new TaxComputationResult(
                TaxTreatment.INTRA_STATE, "27", new BigDecimal("1000"),
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
}
