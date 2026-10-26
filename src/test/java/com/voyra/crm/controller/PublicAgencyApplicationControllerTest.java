package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.security.InquiryRateLimiter;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.KycUploadRateLimiter;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.security.TurnstileVerifier;
import com.voyra.crm.exception.CaptchaException;
import org.springframework.http.HttpStatus;
import com.voyra.crm.service.AgencyApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The apply flow is open to anonymous visitors; uploads and submit are gated by the X-Upload-Token header. */
@WebMvcTest(PublicAgencyApplicationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PublicAgencyApplicationControllerTest {

    private static final String VALID = "{\"agencyName\":\"Sky Trips\",\"pan\":\"ABCDE1234F\",\"ownerName\":\"Asha Rao\","
            + "\"ownerEmail\":\"asha@skytrips.in\",\"ownerPhone\":\"+91 98765 43210\",\"consent\":true}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgencyApplicationService agencyApplicationService;

    @MockBean
    private InquiryRateLimiter inquiryRateLimiter;

    @MockBean
    private KycUploadRateLimiter kycUploadRateLimiter;

    @MockBean
    private TurnstileVerifier turnstileVerifier;

    @org.junit.jupiter.api.BeforeEach
    void caller() {
        given(inquiryRateLimiter.callerOf(any())).willReturn("203.0.113.7");
    }

    @Test
    void createIsOpenToAnonymousAndRateLimited() throws Exception {
        given(agencyApplicationService.create(any()))
                .willReturn(AgencyApplicationCreatedResponse.builder().id("app-1").uploadToken("tok").build());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":\"app-1\",\"uploadToken\":\"tok\"}"));
        verify(inquiryRateLimiter).check(anyString());
        verify(kycUploadRateLimiter, never()).check(anyString());
    }

    @Test
    void createPassesTheCaptchaTokenToTheVerifier() throws Exception {
        given(agencyApplicationService.create(any()))
                .willReturn(AgencyApplicationCreatedResponse.builder().id("app-1").uploadToken("tok").build());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "203.0.113.7").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .content(VALID.replace("}", ",\"captchaToken\":\"cf-token\"}")))
                .andExpect(status().isCreated());
        // Cloudflare's own header is the visitor; the last X-Forwarded-For hop is a proxy and is never sent
        verify(turnstileVerifier).verify("cf-token", "203.0.113.7");
    }

    /** Carried minor: without CF-Connecting-IP no remoteip is sent at all (it is optional for Cloudflare). */
    @Test
    void withoutCloudflaresHeaderNoRemoteIpIsSent() throws Exception {
        given(agencyApplicationService.create(any()))
                .willReturn(AgencyApplicationCreatedResponse.builder().id("app-1").uploadToken("tok").build());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.4, 10.0.0.1")
                        .content(VALID.replace("}", ",\"captchaToken\":\"cf-token\"}")))
                .andExpect(status().isCreated());
        verify(turnstileVerifier).verify("cf-token", null);
    }

    @Test
    void aFailedCaptchaIs400AndNothingIsCreated() throws Exception {
        org.mockito.Mockito.doThrow(new CaptchaException(HttpStatus.BAD_REQUEST, "CAPTCHA_FAILED", "Verification failed, please try again."))
                .when(turnstileVerifier).verify(any(), any());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"CAPTCHA_FAILED\",\"message\":\"Verification failed, please try again.\"}"));
        verify(agencyApplicationService, never()).create(any());
    }

    @Test
    void captchaUnavailableIs503AndNothingIsCreated() throws Exception {
        org.mockito.Mockito.doThrow(new CaptchaException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE", "Verification failed, please try again."))
                .when(turnstileVerifier).verify(any(), any());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"code\":\"CAPTCHA_UNAVAILABLE\"}"));
        verify(agencyApplicationService, never()).create(any());
    }

    @Test
    void uploadsAndSubmitNeverAskForTheCaptcha() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4".getBytes());
        mockMvc.perform(multipart("/api/public/agency-applications/app-1/documents").file(file).param("type", "PAN")
                .header("X-Upload-Token", "tok")).andExpect(status().isOk());
        mockMvc.perform(post("/api/public/agency-applications/app-1/submit").header("X-Upload-Token", "tok"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verifyNoInteractions(turnstileVerifier);
    }

    @Test
    void createValidatesPanFormat() throws Exception {
        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("ABCDE1234F", "abc")))
                .andExpect(status().isBadRequest());
        verify(agencyApplicationService, never()).create(any());
    }

    @Test
    void uploadRequiresTheTokenHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/public/agency-applications/app-1/documents").file(file).param("type", "PAN"))
                .andExpect(status().isBadRequest());
        verify(agencyApplicationService, never()).attachDocument(anyString(), anyString(), any(KycDocumentType.class), any());
    }

    @Test
    void submitAcks() throws Exception {
        mockMvc.perform(post("/api/public/agency-applications/app-1/submit").header("X-Upload-Token", "tok"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"));
        verify(agencyApplicationService).submit("app-1", "tok");
    }

    @Test
    void uploadUsesTheUploadLimiterAndSubmitUsesNeither() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/public/agency-applications/app-1/documents").file(file).param("type", "PAN")
                        .header("X-Upload-Token", "tok"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/public/agency-applications/app-1/submit").header("X-Upload-Token", "tok"))
                .andExpect(status().isOk());
        verify(inquiryRateLimiter, never()).check(anyString());
        verify(kycUploadRateLimiter, org.mockito.Mockito.times(1)).check(anyString());
        verify(agencyApplicationService).attachDocument(org.mockito.ArgumentMatchers.eq("app-1"),
                org.mockito.ArgumentMatchers.eq("tok"), org.mockito.ArgumentMatchers.eq(KycDocumentType.PAN), any());
    }

    @Test
    void anUploadOverTheLimitIs429() throws Exception {
        org.mockito.Mockito.doThrow(new InquiryRateLimiter.TooManyInquiriesException("slow down"))
                .when(kycUploadRateLimiter).check(anyString());
        MockMultipartFile file = new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/public/agency-applications/app-1/documents").file(file).param("type", "PAN")
                        .header("X-Upload-Token", "tok"))
                .andExpect(status().isTooManyRequests());
        verify(agencyApplicationService, never()).attachDocument(anyString(), anyString(), any(KycDocumentType.class), any());
    }

    @Test
    void aWrongTokenOnUploadIs403ForAnAnonymousCaller() throws Exception {
        given(agencyApplicationService.attachDocument(anyString(), anyString(), any(KycDocumentType.class), any()))
                .willThrow(new org.springframework.security.access.AccessDeniedException("This upload link is invalid or has expired"));
        MockMultipartFile file = new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/public/agency-applications/app-1/documents").file(file).param("type", "PAN")
                        .header("X-Upload-Token", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"message\":\"This upload link is invalid or has expired\"}"));
    }
}
