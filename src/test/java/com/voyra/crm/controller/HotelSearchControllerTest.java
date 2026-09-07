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
        when(hotelSearchProvider.search(any())).thenReturn(List.of());
        mockMvc.perform(post("/api/hotels/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchQuery())))
                .andExpect(status().isOk());
    }

    @Test
    void search_ownerAllowed() throws Exception {
        when(hotelSearchProvider.search(any())).thenReturn(List.of());
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
}
