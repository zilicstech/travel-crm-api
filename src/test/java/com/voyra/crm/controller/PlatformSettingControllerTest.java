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
    @MockBean
    private com.voyra.crm.service.PlatformSettingRequestService requests;

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

    /** The live switch: request, list, approve and withdraw are platform-admin only. */
    @Test
    void secondApproverEndpointsArePlatformAdminOnly() throws Exception {
        com.voyra.crm.dto.PlatformSettingRequestResponse r = com.voyra.crm.dto.PlatformSettingRequestResponse.builder()
                .id("R1").settingKey("tripjack_live_ready").value("true").status("PENDING").build();
        given(requests.request("tripjack_live_ready", "true")).willReturn(r);
        given(requests.approve("R1")).willReturn(r);
        given(requests.withdraw("R1")).willReturn(r);
        given(requests.pending()).willReturn(List.of(r));
        var admin = user("a").roles("SUPER_ADMIN");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/platform/settings/tripjack_live_ready/requests")
                .with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value("R1"));
        mockMvc.perform(get("/api/platform/settings/requests").with(admin)).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/platform/settings/requests/R1/approve").with(admin))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/platform/settings/requests/R1/withdraw").with(admin))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/platform/settings/requests/R1/approve")
                .with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
    }
}
