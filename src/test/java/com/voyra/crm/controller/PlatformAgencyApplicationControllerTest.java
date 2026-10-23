package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyApplicationResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The KYC review desk is SUPER_ADMIN only; approve answers 201 with the new, active agency. */
@WebMvcTest(PlatformAgencyApplicationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformAgencyApplicationControllerTest {

    private static final String BASE = "/api/platform/agency-applications";
    private static final String APPROVE_BODY = "{\"ownerPassword\":\"Secret123\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private AgencyApplicationService agencyApplicationService;

    @Test
    void superAdminListsApplications() throws Exception {
        when(agencyApplicationService.list(any(), any())).thenReturn(PagedResponse.<AgencyApplicationResponse>builder()
                .content(List.of(AgencyApplicationResponse.builder().id("app-1").status(ApplicationStatus.SUBMITTED).build()))
                .totalElements(1).build());

        mockMvc.perform(get(BASE).param("status", "SUBMITTED").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("app-1"));
    }

    @Test
    void agencyOwnerCannotListApplications() throws Exception {
        mockMvc.perform(get(BASE).with(user("owner").roles("AGENCY_OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveAnswers201WithTheActiveAgency() throws Exception {
        when(agencyApplicationService.approve("app-1", "Secret123"))
                .thenReturn(AgencyResponse.builder().id("T-1").status(TenantStatus.ACTIVE).isActive(true).build());

        mockMvc.perform(post(BASE + "/app-1/approve").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void agencyOwnerCannotApprove() throws Exception {
        mockMvc.perform(post(BASE + "/app-1/approve").with(user("owner").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isForbidden());
        verify(agencyApplicationService, never()).approve(anyString(), anyString());
    }

    @Test
    void approveNeedsAnEightCharacterPassword() throws Exception {
        mockMvc.perform(post(BASE + "/app-1/approve").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void secondApproveIsAConflict() throws Exception {
        when(agencyApplicationService.approve("app-1", "Secret123"))
                .thenThrow(new IllegalStateException("This application has already been decided"));

        mockMvc.perform(post(BASE + "/app-1/approve").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void decisionReturnsTheUpdatedApplication() throws Exception {
        when(agencyApplicationService.decide(eq("app-1"), any())).thenReturn(AgencyApplicationResponse.builder()
                .id("app-1").status(ApplicationStatus.MORE_INFO).resumeUrl("/apply?application=app-1&token=t").build());

        mockMvc.perform(post(BASE + "/app-1/decision").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MORE_INFO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeUrl").value("/apply?application=app-1&token=t"));
    }

    @Test
    void decisionNeedsAStatus() throws Exception {
        mockMvc.perform(post(BASE + "/app-1/decision").with(user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void documentFileIsServedInline() throws Exception {
        when(agencyApplicationService.openDocument("app-1", "doc-1")).thenReturn(new AgencyApplicationService.KycFileContent(
                new ByteArrayResource("%PDF-1.4".getBytes()), "pan.pdf", "application/pdf"));

        mockMvc.perform(get(BASE + "/app-1/documents/doc-1/file").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("pan.pdf")));
    }
}
