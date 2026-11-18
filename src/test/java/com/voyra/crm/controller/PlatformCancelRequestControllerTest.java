package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AdminCancelRequestResponse;
import com.voyra.crm.dto.CancelRequestResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.CancelReason;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformCancelRequestController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformCancelRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PlatformCancelRequestService service;

    static AdminCancelRequestResponse admin() {
        CancelRequestResponse r = new CancelRequestResponse("R1", CancelRequestStatus.OPEN, CancelReason.TRAVEL_PLAN_CANCELLED, null, false,
                List.of(), List.of(), null, List.of(), null, null, null, false, null, "Asha", Instant.EPOCH, null);
        return new AdminCancelRequestResponse(r, "PB1", "FLIGHT", "T1", "Sky Travels", "ABC123", "2026-12-01", false, null, null, null, false,
                null, new AdminCancelRequestResponse.Prefill(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO), null);
    }

    private static final String COMPLETE = """
            {"finalReason":"TRAVEL_PLAN_CANCELLED","passengers":[0],"sectors":[],"supplierRefund":1000,"charges":200,"voyraFee":0,
             "recall":10,"supplierReference":"AMD-1","note":"Done","exceedsShare":false}""";

    @Test
    void superAdminWorksTheQueue() throws Exception {
        given(service.adminList(eq(CancelRequestStatus.OPEN), eq(0), eq(20)))
                .willReturn(PagedResponse.<AdminCancelRequestResponse>builder().content(List.of(admin())).page(0).size(20).totalElements(1).totalPages(1).build());
        given(service.adminGet("R1")).willReturn(admin());
        given(service.start("R1")).willReturn(admin());
        given(service.reject("R1", "No")).willReturn(admin());
        given(service.complete(eq("R1"), any())).willReturn(admin());
        given(service.adminFile("R1", "F1")).willReturn(new PlatformCancelRequestService.FileContent(new ByteArrayResource("%PDF".getBytes()), "proof.pdf", "application/pdf"));

        mockMvc.perform(get("/api/platform/cancel-requests").param("status", "OPEN").param("page", "0").param("size", "20").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].agencyName").value("Sky Travels"));
        mockMvc.perform(get("/api/platform/cancel-requests/R1").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.id").value("R1"));
        mockMvc.perform(post("/api/platform/cancel-requests/R1/start").with(user("a").roles("SUPER_ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/platform/cancel-requests/R1/reject").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"No\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/platform/cancel-requests/R1/complete").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(COMPLETE)).andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/cancel-requests/R1/files/F1").with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void figuresAndNotesAreValidated() throws Exception {
        for (String body : new String[] {
                COMPLETE.replace("\"supplierRefund\":1000", "\"supplierRefund\":-1"),
                COMPLETE.replace("\"recall\":10", "\"recall\":10.123"),
                COMPLETE.replace("\"finalReason\":\"TRAVEL_PLAN_CANCELLED\",", ""),
                COMPLETE.replace("\"charges\":200,", "")}) {
            mockMvc.perform(post("/api/platform/cancel-requests/R1/complete").with(user("a").roles("SUPER_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/platform/cancel-requests/R1/reject").with(user("a").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\" \"}")).andExpect(status().isBadRequest());
        verify(service, never()).complete(any(), any());
        verify(service, never()).reject(any(), any());
    }

    @Test
    void agencyUsersAreForbidden() throws Exception {
        mockMvc.perform(get("/api/platform/cancel-requests").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform/cancel-requests/R1/complete").with(user("g").roles("AGENT"))
                .contentType(MediaType.APPLICATION_JSON).content(COMPLETE)).andExpect(status().isForbidden());
        verify(service, never()).complete(any(), any());
    }
}
