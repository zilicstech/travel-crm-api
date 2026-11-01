package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PlanPricesRequest;
import com.voyra.crm.dto.PlanPricesResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PlatformPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformPlanController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformPlanControllerTest {

    private static final String OK = "{\"LITE\":2999,\"SUITE\":4999,\"STAFF_SEATS\":399}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PlatformPlanService service;

    private static PlanPricesResponse prices() {
        return new PlanPricesResponse(new BigDecimal("2999"), new BigDecimal("4999"), new BigDecimal("399"));
    }

    @Test
    void superAdminReadsAndSetsPlanPrices() throws Exception {
        given(service.get()).willReturn(prices());
        given(service.set(any(PlanPricesRequest.class))).willReturn(prices());
        mockMvc.perform(get("/api/platform/plans").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.LITE").value(2999)).andExpect(jsonPath("$.SUITE").value(4999))
                .andExpect(jsonPath("$.STAFF_SEATS").value(399));
        mockMvc.perform(put("/api/platform/plans").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(OK))
                .andExpect(status().isOk()).andExpect(jsonPath("$.LITE").value(2999));
    }

    @Test
    void planPricesAreValidated() throws Exception {
        for (String body : new String[] {
                "{\"LITE\":0,\"SUITE\":4999,\"STAFF_SEATS\":399}",
                "{\"LITE\":-1,\"SUITE\":4999,\"STAFF_SEATS\":399}",
                "{\"LITE\":2999.999,\"SUITE\":4999,\"STAFF_SEATS\":399}",
                "{\"LITE\":\"abc\",\"SUITE\":4999,\"STAFF_SEATS\":399}",
                "{\"LITE\":2999,\"SUITE\":4999}",
                "{\"LITE\":1000001,\"SUITE\":4999,\"STAFF_SEATS\":399}"}) {
            mockMvc.perform(put("/api/platform/plans").with(user("a").roles("SUPER_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(service, never()).set(any());
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/plans").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/platform/plans").with(user("o").roles("AGENCY_OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(OK)).andExpect(status().isForbidden());
    }
}
