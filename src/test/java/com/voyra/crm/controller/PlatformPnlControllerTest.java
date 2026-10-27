package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.dto.PnlRow;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformPnlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformPnlController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformPnlControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PlatformPnlService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "admin", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void superAdminGetsThePnl() throws Exception {
        given(service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PricingProduct.FLIGHT, null, SupplierEnvironment.PROD))
                .willReturn(PnlResponse.builder().from(LocalDate.of(2026, 10, 1)).to(LocalDate.of(2026, 10, 31))
                        .totals(PnlRow.builder().key("ALL").bookings(2).voyraNet(new BigDecimal("-12.50")).build())
                        .byAgency(List.of()).byProduct(List.of()).build());
        mockMvc.perform(get("/api/platform/pnl?from=2026-10-01&to=2026-10-31&product=FLIGHT").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.bookings").value(2))
                .andExpect(jsonPath("$.totals.voyraNet").value(-12.5));
    }

    @Test
    void datesAreRequired() throws Exception {
        mockMvc.perform(get("/api/platform/pnl?from=2026-10-01").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/pnl?from=2026-10-01&to=2026-10-31").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void unknownFilterValueIs400() throws Exception {
        mockMvc.perform(get("/api/platform/pnl?from=2026-10-01&to=2026-10-31&product=TRAIN").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
