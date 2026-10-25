package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.TopupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformTopupController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformTopupControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private TopupService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "admin", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void superAdminApproves() throws Exception {
        given(service.approve(eq("R1"), eq("A1"), any())).willReturn(TopupResponse.builder().id("R1")
                .status(TopupStatus.APPROVED).approvedAmount(new BigDecimal("49500.00")).build());
        mockMvc.perform(post("/api/platform/topups/R1/approve").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approvedAmount\":49500.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void secondApprovalIs409() throws Exception {
        given(service.approve(eq("R1"), any(), any())).willThrow(new IllegalStateException("This top-up was already approved"));
        mockMvc.perform(post("/api/platform/topups/R1/approve").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approvedAmount\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This top-up was already approved"));
    }

    @Test
    void rejectNeedsANote() throws Exception {
        mockMvc.perform(post("/api/platform/topups/R1/reject").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void proofStreams() throws Exception {
        given(service.openProof("R1")).willReturn(new TopupService.ProofContent(
                new ByteArrayResource("%PDF".getBytes()), "slip.pdf", "application/pdf"));
        mockMvc.perform(get("/api/platform/topups/R1/proof").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }

    @Test
    void agencyUsersAreForbidden() throws Exception {
        for (UserType t : new UserType[]{UserType.AGENCY_OWNER, UserType.AGENT}) {
            mockMvc.perform(get("/api/platform/topups").with(as(t))).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/platform/topups/R1/approve").with(as(t))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"approvedAmount\":10}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void listFiltersByStatus() throws Exception {
        given(service.listForPlatform(TopupStatus.PENDING)).willReturn(List.of());
        mockMvc.perform(get("/api/platform/topups?status=PENDING").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
    }
}
