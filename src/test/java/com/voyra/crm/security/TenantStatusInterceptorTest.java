package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.config.WebMvcConfig;
import com.voyra.crm.controller.AuthController;
import com.voyra.crm.controller.PlatformBookingController;
import com.voyra.crm.controller.VendorController;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.service.booking.PlatformBookingQueryService;
import com.voyra.crm.service.booking.PlatformBookingService;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A SUSPENDED agency signs in and reads, but every tenant write is refused with 423. */
@WebMvcTest({VendorController.class, AuthController.class, PlatformBookingController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class,
        WebMvcConfig.class, TenantStatusInterceptor.class})
class TenantStatusInterceptorTest {

    private static final String TENANT = "TSUSP1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private VendorService vendorService;
    @MockBean private AuthService authService;
    @MockBean private PlatformBookingService platformBookingService;
    @MockBean private PlatformBookingQueryService platformBookingQueryService;

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
    void suspendedTenantCannotBookButCanReadPlatformBookings() throws Exception {
        for (String path : List.of("/api/platform-bookings/flights/review", "/api/platform-bookings/B1/confirm",
                "/api/platform-bookings/B1/abort", "/api/platform-bookings/B1/cancel")) {
            mockMvc.perform(post(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(423)).andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
        }
        when(platformBookingQueryService.list(any(), any())).thenReturn(PagedResponse.<com.voyra.crm.dto.PlatformBookingResponse>builder()
                .content(List.of()).build());
        mockMvc.perform(get("/api/platform-bookings").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void readStylePostsPassForASuspendedTenant() throws Exception {
        TenantStatusInterceptor interceptor = new TenantStatusInterceptor(new ObjectMapper());
        TenantContext.setTenantId(TENANT);
        try {
            for (String path : List.of("/api/flights/search", "/api/flights/search/paged", "/api/hotels/search",
                    "/api/hotels/thumbnails", "/api/hotels/HTL-42/detail", "/api/clients/duplicate-check",
                    "/api/accounts/invoices/preview-tax")) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", path), response, new Object()))
                        .as(path).isTrue();
            }
            MockHttpServletResponse refused = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/bookings/x/refunds/check"),
                    refused, new Object())).isFalse();
            assertThat(refused.getStatus()).isEqualTo(423);
            // Exact matching: a write nested under an allowed path is still a write.
            assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/hotels/HTL-42/detail/book"),
                    new MockHttpServletResponse(), new Object())).isFalse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("PUT", "/api/clients/duplicate-check"),
                    new MockHttpServletResponse(), new Object())).isFalse();
        } finally {
            TenantContext.clear();
        }
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
