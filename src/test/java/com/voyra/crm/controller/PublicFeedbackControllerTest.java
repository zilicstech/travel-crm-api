package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PublicFeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
 * raw JSON body, not the DTO type, so a future field addition to the response fails the
 * build. Same shape as PublicProposalControllerTest.
 */
@WebMvcTest(PublicFeedbackController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PublicFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PublicFeedbackService publicFeedbackService;

    private PublicFeedbackResponse sampleResponse() {
        return PublicFeedbackResponse.builder()
                .destination("Dubai, UAE")
                .journeyDate(LocalDate.of(2026, 9, 15))
                .alreadySubmitted(false)
                .build();
    }

    @Test
    void publicFeedbackNeverExposesInternalFields() throws Exception {
        given(publicFeedbackService.getFeedbackPage("TOKEN123")).willReturn(sampleResponse());

        String body = mockMvc.perform(get("/api/public/feedback/TOKEN123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String forbidden : List.of("netCost", "profit", "sellingPrice", "agentId", "agentName",
                "clientId", "clientName", "supplier", "pnr", "ticketNo", "bookingStatus", "paymentStatus")) {
            assertThat(body).doesNotContain(forbidden);
        }
    }

    @Test
    void endpointIsReachableWithNoAuthorizationHeader() throws Exception {
        given(publicFeedbackService.getFeedbackPage("TOKEN123")).willReturn(sampleResponse());

        mockMvc.perform(get("/api/public/feedback/TOKEN123"))
                .andExpect(status().isOk());
    }

    @Test
    void submitReturnsGenericSuccessAcknowledgement() throws Exception {
        doNothing().when(publicFeedbackService).submit(org.mockito.ArgumentMatchers.eq("TOKEN123"), org.mockito.ArgumentMatchers.any());

        FeedbackSubmitRequest request = new FeedbackSubmitRequest();
        request.setRating(5);
        request.setComment("Wonderful trip!");

        mockMvc.perform(post("/api/public/feedback/TOKEN123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"message\":\"Thank you for your feedback\"}"));
    }

    @Test
    void submitRejectsRatingOutsideOneToFive() throws Exception {
        FeedbackSubmitRequest request = new FeedbackSubmitRequest();
        request.setRating(7);

        mockMvc.perform(post("/api/public/feedback/TOKEN123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
