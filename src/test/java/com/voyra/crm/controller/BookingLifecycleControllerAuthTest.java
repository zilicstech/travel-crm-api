package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BookingLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every role reads a booking's lifecycle; only the owner and the accountant move its money. */
@WebMvcTest(BookingLifecycleController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class BookingLifecycleControllerAuthTest {

    private static final String PAYMENT = "{\"amount\":100,\"paymentMode\":\"UPI\",\"receivedOn\":\"2026-10-17\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private BookingLifecycleService bookingLifecycleService;

    @Test
    void agentCanReadTheLifecycle() throws Exception {
        when(bookingLifecycleService.getLifecycle("B1")).thenReturn(BookingLifecycleResponse.builder().build());
        mockMvc.perform(get("/api/bookings/B1/lifecycle").with(user("a").roles("AGENT"))).andExpect(status().isOk());
    }

    @Test
    void agentCannotGenerateAnInvoice() throws Exception {
        mockMvc.perform(post("/api/bookings/B1/invoice").with(user("a").roles("AGENT"))).andExpect(status().isForbidden());
    }

    @Test
    void agentCannotRecordAPayment() throws Exception {
        mockMvc.perform(post("/api/bookings/B1/payments").with(user("a").roles("AGENT"))
                .contentType(MediaType.APPLICATION_JSON).content(PAYMENT)).andExpect(status().isForbidden());
    }

    @Test
    void accountantCanRecordAPayment() throws Exception {
        when(bookingLifecycleService.recordPayment(eq("B1"), any())).thenReturn(BookingLifecycleResponse.builder().build());
        mockMvc.perform(post("/api/bookings/B1/payments").with(user("c").roles("ACCOUNTANT"))
                .contentType(MediaType.APPLICATION_JSON).content(PAYMENT)).andExpect(status().isOk());
    }

    @Test
    void aPaymentWithoutAnAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/bookings/B1/payments").with(user("o").roles("AGENCY_OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMode\":\"UPI\",\"receivedOn\":\"2026-10-17\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanGenerateAnInvoiceWithNoBody() throws Exception {
        when(bookingLifecycleService.generateInvoice(eq("B1"), any())).thenReturn(BookingLifecycleResponse.builder().build());
        mockMvc.perform(post("/api/bookings/B1/invoice").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isOk());
    }
}
