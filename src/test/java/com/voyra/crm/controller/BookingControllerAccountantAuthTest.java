package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.FeedbackLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Accounts module bills against any booking agency-wide, so an ACCOUNTANT gains read
 * access here (Epic 3) without gaining any write access - creating/editing a booking is still
 * Owner/Agent work.
 */
@WebMvcTest(BookingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class BookingControllerAccountantAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingService bookingService;
    @MockBean
    private FeedbackLinkService feedbackLinkService;

    @Test
    void accountantCanListAndReadBookings() throws Exception {
        when(bookingService.listBookings(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/bookings")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());

        when(bookingService.getBooking("B1")).thenReturn(BookingResponse.builder().id("B1").build());
        mockMvc.perform(get("/api/bookings/B1")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());
    }

    @Test
    void accountantCannotCreateABooking() throws Exception {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setClientId("K1");
        request.setType(BookingType.FLIGHT);
        request.setDestination("Dubai, UAE");
        request.setNetCost(java.math.BigDecimal.valueOf(42000));
        request.setSellingPrice(java.math.BigDecimal.valueOf(52000));

        mockMvc.perform(post("/api/bookings")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
