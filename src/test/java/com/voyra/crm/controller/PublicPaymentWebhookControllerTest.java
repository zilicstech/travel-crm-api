package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.GatewayWebhookService;
import com.voyra.crm.service.GatewayWebhookService.WebhookOutcome;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anonymous, signed by Razorpay; the raw body reaches the service byte for byte. */
@WebMvcTest(PublicPaymentWebhookController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PublicPaymentWebhookControllerTest {

    private static final String URL = "/api/public/payments/razorpay/webhook";
    private static final String BODY = "{\"event\":\"payment.captured\", \"payload\":{}}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private GatewayWebhookService webhookService;
    @MockBean
    private PaymentGateway gateway;

    @BeforeEach
    void razorpay() {
        given(gateway.name()).willReturn("razorpay");
    }

    private static MockHttpServletRequestBuilder webhook() {
        return post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY).header("X-Razorpay-Signature", "abc");
    }

    @Test
    void processedIs200AndTheRawBodyIsPassedThrough() throws Exception {
        given(webhookService.handle(BODY, "abc")).willReturn(WebhookOutcome.PROCESSED);
        mockMvc.perform(webhook()).andExpect(status().isOk());
        verify(webhookService).handle(BODY, "abc");
    }

    @Test
    void retryIs503() throws Exception {
        given(webhookService.handle(anyString(), any())).willReturn(WebhookOutcome.RETRY);
        mockMvc.perform(webhook()).andExpect(status().isServiceUnavailable());
    }

    @Test
    void badSignatureIs400() throws Exception {
        given(webhookService.handle(anyString(), any())).willThrow(new IllegalArgumentException("Invalid webhook signature"));
        mockMvc.perform(webhook()).andExpect(status().isBadRequest());
    }

    @Test
    void stubProviderHasNoPublicWebhook() throws Exception {
        given(gateway.name()).willReturn("stub");
        mockMvc.perform(webhook()).andExpect(status().isNotFound());
        verifyNoInteractions(webhookService);
    }
}
