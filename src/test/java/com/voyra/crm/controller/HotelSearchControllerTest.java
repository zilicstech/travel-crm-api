package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.HotelSearchQuery;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.HotelSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mirrors FlightSearchController's auth shape: owner and agent both reach it, no one else does. */
@WebMvcTest(HotelSearchController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class HotelSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HotelSearchProvider hotelSearchProvider;
    @MockBean
    private com.voyra.crm.service.HotelSearchService hotelSearchService;

    private HotelSearchQuery searchQuery() {
        HotelSearchQuery query = new HotelSearchQuery();
        query.setCity("Phuket");
        query.setCheckIn(LocalDate.of(2026, 10, 9));
        query.setCheckOut(LocalDate.of(2026, 10, 12));
        query.setRooms(1);
        query.setAdults(2);
        return query;
    }

    @Test
    void search_unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/hotels/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void search_agentAllowed() throws Exception {
        when(hotelSearchService.search(any())).thenReturn(new com.voyra.crm.dto.HotelSearchPageResponse());
        mockMvc.perform(post("/api/hotels/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isOk());
    }

    @Test
    void search_ownerAllowed() throws Exception {
        when(hotelSearchService.search(any())).thenReturn(new com.voyra.crm.dto.HotelSearchPageResponse());
        mockMvc.perform(post("/api/hotels/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("owner1").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isOk());
    }

    /** Same isolation rule as flight search: platform admins manage agencies, never business data. */
    @Test
    void search_superAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/hotels/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isForbidden());
    }

    @Test
    void thumbnails_agentGetsThemAndAnEmptyListIsRejected() throws Exception {
        when(hotelSearchProvider.thumbnails(any())).thenReturn(List.of());
        mockMvc.perform(post("/api/hotels/thumbnails")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelIds\":[\"1001\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/hotels/thumbnails")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /** An agency must never be able to work out Voyra's earnings or net from a hotel response. */
    @Test
    void detail_rawJsonHasNoVoyraFiguresOrBaseFare() throws Exception {
        var option = com.voyra.crm.dto.HotelDetailResponse.RoomOption.builder().optionId("o1").price(new java.math.BigDecimal("5000"))
                .baseFare(new java.math.BigDecimal("4100")).publishedFare(new java.math.BigDecimal("5000"))
                .displayedFare(new java.math.BigDecimal("4900")).agencyEarns(new java.math.BigDecimal("50")).bookable(true).build();
        when(hotelSearchService.detail(any(), any())).thenReturn(com.voyra.crm.dto.HotelDetailResponse.builder()
                .hotelId("h1").options(List.of(option)).build());
        mockMvc.perform(post("/api/hotels/h1/detail")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.options[0].displayedFare").value(4900))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.options[0].voyraEarned").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.options[0].voyraNet").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.options[0].baseFare").doesNotExist());
    }

    @Test
    void search_rawJsonHasNoBaseFare() throws Exception {
        var page = com.voyra.crm.dto.HotelSearchPageResponse.builder().offers(List.of(com.voyra.crm.dto.HotelOfferResponse.builder()
                .hotelId("h1").baseFare(new java.math.BigDecimal("4100")).publishedFare(new java.math.BigDecimal("5000")).build())).build();
        when(hotelSearchService.search(any())).thenReturn(page);
        mockMvc.perform(post("/api/hotels/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.offers[0].publishedFare").value(5000))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.offers[0].baseFare").doesNotExist());
    }
}
