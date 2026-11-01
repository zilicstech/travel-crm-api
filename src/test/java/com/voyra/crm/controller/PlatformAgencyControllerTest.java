package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.service.AgencyProfileService;
import com.voyra.crm.service.AgencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agency status is a platform-only control and takes the three-state status, not a boolean. */
@WebMvcTest(PlatformAgencyController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformAgencyControllerTest {

    private static final String BODY = "{\"status\":\"SUSPENDED\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private AgencyService agencyService;
    @MockBean private AgencyProfileService agencyProfileService;

    private static final MockMultipartFile LOGO = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1});

    @Test
    void superAdminCanSuspendAnAgency() throws Exception {
        when(agencyService.updateStatus("T1", TenantStatus.SUSPENDED))
                .thenReturn(AgencyResponse.builder().id("T1").status(TenantStatus.SUSPENDED).isActive(true).build());

        mockMvc.perform(patch("/api/platform/agencies/T1/status")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        verify(agencyService).updateStatus(eq("T1"), eq(TenantStatus.SUSPENDED));
    }

    @Test
    void agencyOwnerCannotChangeAgencyStatus() throws Exception {
        mockMvc.perform(patch("/api/platform/agencies/T1/status")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingStatusIsABadRequest() throws Exception {
        mockMvc.perform(patch("/api/platform/agencies/T1/status")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void superAdminSetsAnAgencysLogo() throws Exception {
        when(agencyProfileService.uploadLogoForAgency(eq("T1"), any()))
                .thenReturn(AgencyProfileResponse.builder().agencyName("Desert Trips").hasLogo(true).build());

        mockMvc.perform(multipart("/api/platform/agencies/T1/logo").file(LOGO).with(r -> { r.setMethod("PUT"); return r; })
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasLogo").value(true));
        verify(agencyProfileService).uploadLogoForAgency(eq("T1"), any());
    }

    @Test
    void agencyUsersCannotSetAnotherAgencysLogo() throws Exception {
        for (String role : new String[]{"AGENCY_OWNER", "AGENT"}) {
            mockMvc.perform(multipart("/api/platform/agencies/T1/logo").file(LOGO).with(r -> { r.setMethod("PUT"); return r; })
                            .with(SecurityMockMvcRequestPostProcessors.user("u").roles(role)))
                    .andExpect(status().isForbidden());
        }
        verify(agencyProfileService, never()).uploadLogoForAgency(any(), any());
    }

    @Test
    void anUnknownAgencysLogoIs404() throws Exception {
        when(agencyProfileService.uploadLogoForAgency(eq("NOPE"), any())).thenThrow(new NotFoundException("Agency not found: NOPE"));

        mockMvc.perform(multipart("/api/platform/agencies/NOPE/logo").file(LOGO).with(r -> { r.setMethod("PUT"); return r; })
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
