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
    @MockBean
    private com.voyra.crm.service.FlightFareCalendarService fareCalendarService;

    @Test
    void theFareCalendarCarriesOnlyDaysAndFares() throws Exception {
        java.time.LocalDate from = java.time.LocalDate.of(2027, 1, 1);
        when(fareCalendarService.read("DEL", "BOM", com.voyra.crm.enums.FlightCabin.BUSINESS, from, from.plusDays(30)))
                .thenReturn(List.of(new com.voyra.crm.dto.FlightFareDayResponse(from.plusDays(2), new BigDecimal("4484.00"))));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/flights/calendar")
                        .param("origin", "DEL").param("destination", "BOM").param("cabin", "BUSINESS")
                        .param("from", "2027-01-01").param("to", "2027-01-31")
                        .with(SecurityMockMvcRequestPostProcessors.user("a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2027-01-03"))
                .andExpect(jsonPath("$[0].fare").value(4484.00))
                .andExpect(jsonPath("$[0].searchedAt").doesNotExist());
    }

    @Test
    void offersAndPagesCarryTheirSupplierOnTheWire() throws Exception {
        FlightOfferResponse offer = FlightOfferResponse.builder().offerId("s1:o1").supplierId("s1").supplierName("Mock supplier").build();
        when(flightSearchService.search(any())).thenReturn(List.of(offer));
        when(flightSearchService.searchPage(any())).thenReturn(com.voyra.crm.dto.FlightSearchPageResponse.builder()
                .flights(List.of(com.voyra.crm.dto.FlightGroupResponse.builder().fares(List.of(offer)).build()))
                .suppliersFailed(List.of("Tripjack")).build());
        String body = "{\"origin\":\"BLR\",\"destination\":\"GOI\",\"departDate\":\"2026-11-25\",\"adults\":1}";
        mockMvc.perform(post("/api/flights/search").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$[0].offerId").value("s1:o1"))
                .andExpect(jsonPath("$[0].supplierId").value("s1"))
                .andExpect(jsonPath("$[0].supplierName").value("Mock supplier"));
        mockMvc.perform(post("/api/flights/search/paged").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":" + body + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights[0].fares[0].supplierId").value("s1"))
                .andExpect(jsonPath("$.flights[0].fares[0].supplierName").value("Mock supplier"))
                .andExpect(jsonPath("$.suppliersFailed[0]").value("Tripjack"));
    }

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

    @Test
    void fareRulesNeedAnOfferAndCarryOnlyTheRules() throws Exception {
        when(flightSearchService.fareRules("s1:o1")).thenReturn(new com.voyra.crm.dto.FlightFareRulesResponse("Rules"));
        mockMvc.perform(post("/api/flights/fare-rules").with(SecurityMockMvcRequestPostProcessors.user("a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"s1:o1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fareRules").value("Rules"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(post("/api/flights/fare-rules").with(SecurityMockMvcRequestPostProcessors.user("a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/flights/fare-rules").with(SecurityMockMvcRequestPostProcessors.user("p").roles("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"s1:o1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePagedSearchCarriesTheNewFareFieldsAndFacetsAndRefusesBadFilters() throws Exception {
        FlightOfferResponse offer = FlightOfferResponse.builder().offerId("s1:o1").refundType(com.voyra.crm.enums.FlightRefundType.PARTIAL)
                .refundable(true).cabinBaggage("7 Kg").seatsLeft(3).mealIncluded(true).build();
        when(flightSearchService.searchPage(any())).thenReturn(com.voyra.crm.dto.FlightSearchPageResponse.builder()
                .flights(List.of(com.voyra.crm.dto.FlightGroupResponse.builder().fares(List.of(offer)).build()))
                .priceMin(new BigDecimal("4000.50")).priceMax(new BigDecimal("9000")).fareTypes(List.of("PUBLISHED"))
                .origins(List.of("DEL")).destinations(List.of("BOM", "NMI")).build());
        String query = "\"query\":{\"origin\":\"BLR\",\"destination\":\"GOI\",\"departDate\":\"2026-11-25\",\"adults\":1}";
        mockMvc.perform(post("/api/flights/search/paged").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{" + query + ",\"stops\":3,\"minPrice\":0,\"maxPrice\":9000,"
                                + "\"departBuckets\":[\"EARLY\"],\"arriveBuckets\":[\"EVENING\"],\"fareTypes\":[\"PUBLISHED\"],"
                                + "\"refundTypes\":[\"PARTIAL\"],\"flightNumber\":\"SG671\",\"origins\":[\"DEL\"],"
                                + "\"destinations\":[\"BOM\"],\"baggage\":\"CHECK_IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights[0].fares[0].refundType").value("PARTIAL"))
                .andExpect(jsonPath("$.flights[0].fares[0].cabinBaggage").value("7 Kg"))
                .andExpect(jsonPath("$.flights[0].fares[0].seatsLeft").value(3))
                .andExpect(jsonPath("$.flights[0].fares[0].mealIncluded").value(true))
                .andExpect(jsonPath("$.priceMin").value(4000.50))
                .andExpect(jsonPath("$.priceMax").value(9000))
                .andExpect(jsonPath("$.fareTypes[0]").value("PUBLISHED"))
                .andExpect(jsonPath("$.origins[0]").value("DEL"))
                .andExpect(jsonPath("$.destinations[1]").value("NMI"));
        for (String bad : List.of("\"stops\":4", "\"minPrice\":-1", "\"maxPrice\":-0.01", "\"flightNumber\":\"ABCDEFGHIJK\"",
                "\"fareTypes\":[\" \"]")) {
            mockMvc.perform(post("/api/flights/search/paged").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{" + query + "," + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void theSearchCarriesItsFareCategoryAndRefusesAnUnknownOne() throws Exception {
        when(flightSearchService.search(any())).thenReturn(List.of());
        String body = "{\"origin\":\"BLR\",\"destination\":\"GOI\",\"departDate\":\"2099-11-25\",\"adults\":1,\"fareCategory\":\"%s\"}";
        mockMvc.perform(post("/api/flights/search").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("SENIOR_CITIZEN")))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(flightSearchService).search(org.mockito.ArgumentMatchers.argThat(
                q -> q.getFareCategory() == com.voyra.crm.enums.FareCategory.SENIOR_CITIZEN));
        mockMvc.perform(post("/api/flights/search").with(SecurityMockMvcRequestPostProcessors.user("o").roles("AGENCY_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("ARMED_FORCES")))
                .andExpect(status().isBadRequest());
    }
}
