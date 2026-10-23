package com.voyra.crm.security;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.config.WebMvcConfig;
import com.voyra.crm.controller.AuthController;
import com.voyra.crm.controller.VendorController;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.service.AuthService;
import com.voyra.crm.service.VendorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A SUSPENDED agency signs in and reads, but every tenant write is refused with 423. */
@WebMvcTest({VendorController.class, AuthController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class,
        WebMvcConfig.class, TenantStatusInterceptor.class})
class TenantStatusInterceptorTest {

    private static final String TENANT = "TSUSP1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private VendorService vendorService;
    @MockBean private AuthService authService;

    private String bearer;

    @BeforeEach
    void suspendedOwner() {
        TenantCache.put(TENANT, "A", TenantStatus.SUSPENDED);
        bearer = "Bearer " + jwtService.generateToken("owner@a.com", UserType.AGENCY_OWNER, TENANT, TENANT);
    }

    @AfterEach
    void reactivate() {
        TenantCache.put(TENANT, "A", TenantStatus.ACTIVE);
    }

    @Test
    void suspendedTenantGets423OnWrites() throws Exception {
        mockMvc.perform(post("/api/vendors").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Air Co\"}"))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
    }

    @Test
    void suspendedTenantCanStillRead() throws Exception {
        mockMvc.perform(get("/api/vendors").header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void authRoutesAreNeverBlocked() throws Exception {
        when(authService.loginOwner(any())).thenReturn(LoginResponse.builder().success(true).build());
        mockMvc.perform(post("/api/auth/login/owner").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@a.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk());
    }
}
