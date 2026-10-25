package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.SubscriptionAdminResponse;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.SubscriptionBillingService;
import com.voyra.crm.service.SubscriptionPaymentService;
import com.voyra.crm.service.SubscriptionService;
import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.dto.SubscriptionPaymentResponse;
import org.springframework.core.io.ByteArrayResource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PlatformAgencySubscriptionController.class, AgencySubscriptionController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformAgencySubscriptionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SubscriptionService service;
    @MockBean private SubscriptionBillingService billingService;
    @MockBean private SubscriptionPaymentService paymentService;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "u", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void superAdminReadsAndChanges() throws Exception {
        given(service.adminView("T1")).willReturn(SubscriptionAdminResponse.builder().plan(SubscriptionPlan.SUITE).build());
        given(service.update(eq("T1"), any())).willReturn(SubscriptionAdminResponse.builder().plan(SubscriptionPlan.CUSTOM).build());

        mockMvc.perform(get("/api/platform/agencies/T1/subscription").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("SUITE"));
        mockMvc.perform(put("/api/platform/agencies/T1/subscription").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"CUSTOM\",\"skus\":[\"CRM_CORE\"],\"seats\":1,\"billingCycle\":\"MONTHLY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("CUSTOM"));
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/waive").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"COMPLIMENTARY\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/extend-trial").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":7}"))
                .andExpect(status().isOk());
        verify(service).waive(eq("T1"), any());
        verify(service).extendTrial(eq("T1"), any());
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/extend-trial").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/waive").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void ownersCannotUseThePlatformRoutes() throws Exception {
        mockMvc.perform(get("/api/platform/agencies/T1/subscription").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void ownerReadsTheirOwnSubscriptionButAgentsCannot() throws Exception {
        mockMvc.perform(get("/api/agency/subscription").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mockMvc.perform(get("/api/agency/subscription").with(as(UserType.AGENT))).andExpect(status().isForbidden());
    }

    @Test
    void superAdminListsRaisesMarksPaidVoidsAndDownloadsInvoices() throws Exception {
        SubscriptionInvoiceResponse inv = SubscriptionInvoiceResponse.builder().id("I1").status("ISSUED").build();
        given(billingService.listForPlatform("T1")).willReturn(List.of(inv));
        given(billingService.raiseNow("T1")).willReturn(inv);
        given(billingService.markPaidByBank("T1", "I1", "UTR1")).willReturn(SubscriptionInvoiceResponse.builder().id("I1").status("PAID").build());
        given(billingService.voidInvoice("T1", "I1", "Plan changed")).willReturn(SubscriptionInvoiceResponse.builder().id("I1").status("VOID").build());
        given(billingService.pdf("T1", "I1")).willReturn(new SubscriptionBillingService.InvoicePdf(new ByteArrayResource("%PDF".getBytes()), "VOY-SUB-2026-1.pdf"));

        mockMvc.perform(get("/api/platform/agencies/T1/subscription/invoices").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("I1"));
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/raise").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/I1/mark-paid").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"UTR1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/I1/void").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Plan changed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOID"));
        mockMvc.perform(get("/api/platform/agencies/T1/subscription/invoices/I1/pdf").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void markPaidNeedsAReferenceAndVoidAReason() throws Exception {
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/I1/mark-paid").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/I1/void").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(billingService);
    }

    @Test
    void ownerPaysSimulatesAndDownloadsOwnInvoicesAgentsCannot() throws Exception {
        given(paymentService.pay("T1", "I1")).willReturn(SubscriptionPaymentResponse.builder().invoiceId("I1")
                .gatewayOrderId("order_1").provider("stub").build());
        given(paymentService.simulate("T1", "I1")).willReturn(SubscriptionInvoiceResponse.builder().id("I1").status("PAID").build());
        given(billingService.pdf("T1", "I1")).willReturn(new SubscriptionBillingService.InvoicePdf(new ByteArrayResource("%PDF".getBytes()), "VOY-SUB-2026-1.pdf"));

        mockMvc.perform(post("/api/agency/subscription/invoices/I1/pay").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gatewayOrderId").value("order_1"));
        mockMvc.perform(post("/api/agency/subscription/invoices/I1/simulate").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/agency/subscription/invoices/I1/pdf").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/agency/subscription/invoices/I1/pay").with(as(UserType.AGENT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform/agencies/T1/subscription/invoices/I1/mark-paid").with(as(UserType.AGENCY_OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"UTR1\"}"))
                .andExpect(status().isForbidden());
        verify(paymentService).pay("T1", "I1");
        verify(billingService, org.mockito.Mockito.never()).markPaidByBank(any(), any(), any());
    }
}
