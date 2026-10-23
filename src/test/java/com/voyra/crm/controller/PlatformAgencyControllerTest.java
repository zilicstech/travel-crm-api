package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
}
