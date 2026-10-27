package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformAdminBookingResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.booking.PlatformBookingQueryService;
import com.voyra.crm.service.booking.PlatformBookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformAdminBookingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformAdminBookingControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformBookingService service;
    @MockBean private PlatformBookingQueryService queryService;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root", type, type == UserType.SUPER_ADMIN ? null : "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void superAdminListsWithFiltersAndSeesVoyraMoney() throws Exception {
        given(queryService.adminList(eq(0), eq(25), eq(PlatformBookingStatus.UNKNOWN), eq("T1")))
                .willReturn(PagedResponse.<PlatformAdminBookingResponse>builder().content(List.of(
                        PlatformAdminBookingResponse.builder().id("B1").voyraNet(new BigDecimal("145.00")).build())).build());

        mockMvc.perform(get("/api/platform/bookings?page=0&size=25&status=UNKNOWN&tenantId=T1").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].voyraNet").value(145.00));
    }

    @Test
    void superAdminResolvesAsTicketed() throws Exception {
        PlatformBooking row = PlatformBooking.builder().id("B1").build();
        given(service.resolve(eq("B1"), eq(PlatformBookingStatus.TICKETED), eq("ABC123"), eq(List.of("098-1")), eq("seen on portal")))
                .willReturn(row);
        given(queryService.adminView(row)).willReturn(PlatformAdminBookingResponse.builder().id("B1").status(PlatformBookingStatus.TICKETED).build());

        mockMvc.perform(post("/api/platform/bookings/B1/resolve").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"TICKETED\",\"pnr\":\"ABC123\",\"ticketNumbers\":[\"098-1\"],\"note\":\"seen on portal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TICKETED"));
    }

    @Test
    void superAdminResolvesACancellation() throws Exception {
        PlatformBooking row = PlatformBooking.builder().id("B1").build();
        given(service.resolveCancel("B1", true, new BigDecimal("4000.00"), "airline confirmed")).willReturn(row);
        given(queryService.adminView(row)).willReturn(PlatformAdminBookingResponse.builder().id("B1").status(PlatformBookingStatus.CANCELLED).build());

        mockMvc.perform(post("/api/platform/bookings/B1/resolve-cancel").with(as(UserType.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelled\":true,\"refund\":4000.00,\"note\":\"airline confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(service).resolveCancel("B1", true, new BigDecimal("4000.00"), "airline confirmed");
    }

    @Test
    void resolveNeedsANoteAndAnOutcome() throws Exception {
        mockMvc.perform(post("/api/platform/bookings/B1/resolve").with(as(UserType.SUPER_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"TICKETED\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void everyoneElseIsForbidden() throws Exception {
        for (UserType type : List.of(UserType.AGENCY_OWNER, UserType.AGENT, UserType.ACCOUNTANT)) {
            mockMvc.perform(get("/api/platform/bookings").with(as(type))).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/platform/bookings/B1/resolve").with(as(type))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FAILED\",\"note\":\"x\"}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
}
