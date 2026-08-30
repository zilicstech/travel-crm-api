package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PublicProposalItemResponse;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PublicProposalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The most important test in the suite: asserts the pricing-safety guarantee against the
 * raw JSON body, not the DTO type, so a future field addition to the response fails the build.
 */
@WebMvcTest(PublicProposalController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PublicProposalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicProposalService publicProposalService;

    private PublicProposalResponse sampleResponse() {
        return PublicProposalResponse.builder()
                .clientName("Jane Doe")
                .destination("Dubai, UAE")
                .travelDateFrom(LocalDate.of(2026, 9, 15))
                .travelDateTo(LocalDate.of(2026, 9, 22))
                .guestCount(2)
                .items(List.of(PublicProposalItemResponse.builder()
                        .type(ProposalItemType.HOTEL)
                        .description("5 nights, Deluxe Suite")
                        .supplier("Cleartrip")
                        .sellingPrice(new BigDecimal("22000.00"))
                        .build()))
                .grandTotal(new BigDecimal("22000.00"))
                .build();
    }

    @Test
    void publicProposalNeverExposesInternalFields() throws Exception {
        given(publicProposalService.getProposal("TOKEN123")).willReturn(sampleResponse());

        String body = mockMvc.perform(get("/api/public/proposals/TOKEN123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String forbidden : List.of("netCost", "margin", "marginPercent", "status", "priority",
                "source", "assignedTo", "notes", "visaTracker", "phone", "email", "budget",
                "lostReason", "clientId",
                // The traveller manifest must never cross the public boundary: it carries
                // passport numbers and dates of birth behind a link that needs no login.
                "members", "memberName", "passportNumber", "passportExpiry", "dob",
                "manifestComplete", "paxType")) {
            assertThat(body).doesNotContain(forbidden);
        }
    }

    @Test
    void endpointIsReachableWithNoAuthorizationHeader() throws Exception {
        given(publicProposalService.getProposal("TOKEN123")).willReturn(sampleResponse());

        mockMvc.perform(get("/api/public/proposals/TOKEN123"))
                .andExpect(status().isOk());
    }

    @Test
    void approveReturnsGenericSuccessAcknowledgement() throws Exception {
        doNothing().when(publicProposalService).approveProposal("TOKEN123");

        mockMvc.perform(post("/api/public/proposals/TOKEN123/approve"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"message\":\"Proposal approved\"}"));
    }
}
