package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.security.InquiryRateLimiter;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
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

    @Test
    void createIsOpenToAnonymousAndRateLimited() throws Exception {
        given(agencyApplicationService.create(any()))
                .willReturn(AgencyApplicationCreatedResponse.builder().id("app-1").uploadToken("tok").build());

        mockMvc.perform(post("/api/public/agency-applications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":\"app-1\",\"uploadToken\":\"tok\"}"));
        verify(inquiryRateLimiter).check(anyString());
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
}
