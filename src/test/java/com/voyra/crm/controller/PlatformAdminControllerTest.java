package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlatformAdminResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformAdminService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformAdminController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformAdminService service;

    private static PlatformAdminResponse admin(String id, boolean active, String password) {
        return PlatformAdminResponse.builder().id(id).name("Admin").email("a@voyra.test").active(active).password(password).build();
    }

    @Test
    void superAdminListsAddsAndTogglesAdmins() throws Exception {
        var sa = user("a").roles("SUPER_ADMIN");
        given(service.list()).willReturn(List.of(admin("SA1", true, null)));
        given(service.create(any())).willReturn(admin("SA2", true, "Gen3ratedPassw0rd"));
        given(service.deactivate("SA2")).willReturn(admin("SA2", false, null));
        given(service.reactivate("SA2")).willReturn(admin("SA2", true, null));

        mockMvc.perform(get("/api/platform/admins").with(sa)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("SA1")).andExpect(jsonPath("$[0].password").doesNotExist());
        mockMvc.perform(post("/api/platform/admins").with(sa).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Priya\",\"email\":\"priya@voyra.test\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.password").value("Gen3ratedPassw0rd"));
        mockMvc.perform(post("/api/platform/admins/SA2/deactivate").with(sa)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(post("/api/platform/admins/SA2/reactivate").with(sa)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void addValidatesNameAndEmail() throws Exception {
        mockMvc.perform(post("/api/platform/admins").with(user("a").roles("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }

    @Test
    void theLastActiveAdminIs409() throws Exception {
        given(service.deactivate("SA1")).willThrow(new IllegalStateException("This is the only active platform admin"));
        mockMvc.perform(post("/api/platform/admins/SA1/deactivate").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This is the only active platform admin"));
    }

    @Test
    void agencyRolesAreForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/admins").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform/admins/SA1/deactivate").with(user("o").roles("AGENT"))).andExpect(status().isForbidden());
        verify(service, never()).deactivate(any());
    }
}
