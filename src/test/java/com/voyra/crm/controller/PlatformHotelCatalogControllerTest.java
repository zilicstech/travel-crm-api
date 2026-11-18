package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.HotelCatalogStatusResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.HotelCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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

@WebMvcTest(PlatformHotelCatalogController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformHotelCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private HotelCatalogService service;

    private static HotelCatalogStatusResponse running() {
        return new HotelCatalogStatusResponse("s1", "Tripjack", "RUNNING", 0, null, null, null, null);
    }

    @Test
    void superAdminStartsALoadAndReadsTheStatus() throws Exception {
        given(service.refresh("s1")).willReturn(running());
        given(service.status()).willReturn(List.of(running()));
        mockMvc.perform(post("/api/platform/hotel-catalog/s1/refresh").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RUNNING"));
        mockMvc.perform(get("/api/platform/hotel-catalog").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].supplierName").value("Tripjack"));
    }

    @Test
    void agencyUsersAreForbidden() throws Exception {
        mockMvc.perform(post("/api/platform/hotel-catalog/s1/refresh").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/hotel-catalog").with(user("g").roles("AGENT"))).andExpect(status().isForbidden());
        verify(service, never()).refresh(any());
    }
}
