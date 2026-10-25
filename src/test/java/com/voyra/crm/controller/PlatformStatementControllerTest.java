package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.StatementImportResponse;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.TripjackStatementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformStatementController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformStatementControllerTest {

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "tj.csv", "text/csv",
            "booking_id,pnr,type,amount,commission,date\nTJ-1,,SALE,1,1,2026-10-05\n".getBytes());

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private TripjackStatementService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "admin", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void superAdminImports() throws Exception {
        given(service.importStatement(any(), eq("A1"), eq("admin")))
                .willReturn(StatementImportResponse.builder().id("I1").appliedLines(1).lines(List.of()).build());
        mockMvc.perform(multipart("/api/platform/statements").file(FILE).with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appliedLines").value(1));
    }

    @Test
    void theSameFileAgainIs409() throws Exception {
        given(service.importStatement(any(), any(), any())).willThrow(new IllegalStateException("Already imported"));
        mockMvc.perform(multipart("/api/platform/statements").file(FILE).with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Already imported"));
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(multipart("/api/platform/statements").file(FILE).with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void theFileIsRequired() throws Exception {
        mockMvc.perform(multipart("/api/platform/statements").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
