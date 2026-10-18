package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.InquiryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public POST is open to anonymous visitors; the platform read is SUPER_ADMIN only. */
@WebMvcTest({PublicInquiryController.class, PlatformInquiryController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class InquiryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InquiryService inquiryService;

    private void postJson(String json, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mockMvc.perform(post("/api/public/inquiries").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(expected);
    }

    @Test
    void publicSubmitWorksWithNoAuthorizationHeader() throws Exception {
        postJson("{\"source\":\"contact\",\"name\":\"John\",\"email\":\"john@acme.com\",\"message\":\"Hi\"}", status().isOk());
        verify(inquiryService).submit(any());
    }

    @Test
    void publicSubmitRejectsBadEmailMissingSourceAndOverlongFields() throws Exception {
        postJson("{\"source\":\"contact\",\"email\":\"not-an-email\"}", status().isBadRequest());
        postJson("{\"email\":\"john@acme.com\"}", status().isBadRequest());
        postJson("{\"source\":\"Contact Page!\",\"email\":\"john@acme.com\"}", status().isBadRequest());
        postJson("{\"source\":\"contact\",\"email\":\"john@acme.com\",\"message\":\"" + "x".repeat(2001) + "\"}",
                status().isBadRequest());
        verify(inquiryService, never()).submit(any());
    }

    /** A bot that fills the honeypot gets the same success body as a person - the service decides not to store it. */
    @Test
    void honeypotSubmissionGetsTheSameSuccessResponse() throws Exception {
        given(inquiryService.submit(any())).willReturn(false);
        mockMvc.perform(post("/api/public/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"contact\",\"email\":\"bot@spam.com\",\"website\":\"http://spam\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"message\":\"Inquiry received\"}"));
    }

    @Test
    void platformListIsSuperAdminOnly() throws Exception {
        given(inquiryService.list(eq(null), any())).willReturn(PagedResponse.<com.voyra.crm.dto.InquiryResponse>builder()
                .content(List.of()).page(0).size(25).totalElements(0).totalPages(0).build());

        mockMvc.perform(get("/api/platform/inquiries")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/platform/inquiries").with(user("owner").roles("AGENCY_OWNER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/inquiries").with(user("agent").roles("AGENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/inquiries").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"page\":0}"));
    }
}
