package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformSettingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PlatformSettingService service;

    @Test
    void superAdminCanListAndUpdate() throws Exception {
        given(service.list()).willReturn(List.of(PlatformSettingResponse.builder().key("tds_rate").value("0").build()));
        mockMvc.perform(get("/api/platform/settings").with(user("a").roles("SUPER_ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(put("/api/platform/settings/tds_rate").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"2\"}")).andExpect(status().isOk());
        // a blank value reaches the service, which decides per key (voyra_state_code may be cleared); a missing one is 400
        mockMvc.perform(put("/api/platform/settings/voyra_state_code").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/platform/settings/tds_rate").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/settings").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
    }
}
