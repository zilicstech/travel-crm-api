package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.FlightSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightSearchController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class FlightSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private FlightSearchService flightSearchService;

    @Test
    void agencyJsonNeverCarriesVoyraEarningsOrBase() throws Exception {
        when(flightSearchService.search(any())).thenReturn(List.of(FlightOfferResponse.builder().offerId("o1")
                .totalPrice(new BigDecimal("5000")).baseFare(new BigDecimal("4000")).publishedFare(new BigDecimal("5000"))
                .displayedFare(new BigDecimal("4500")).agencyEarns(new BigDecimal("100")).bookable(true).build()));
        mockMvc.perform(post("/api/flights/search")
                        .with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"BLR\",\"destination\":\"GOI\",\"departDate\":\"2026-11-25\",\"adults\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayedFare").value(4500))
                .andExpect(jsonPath("$[0].agencyEarns").value(100))
                .andExpect(jsonPath("$[0].voyraEarned").doesNotExist())
                .andExpect(jsonPath("$[0].voyraNet").doesNotExist())
                .andExpect(jsonPath("$[0].baseFare").doesNotExist());
    }
}
