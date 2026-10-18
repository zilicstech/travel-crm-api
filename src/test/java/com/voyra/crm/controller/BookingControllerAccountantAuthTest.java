package com.voyra.crm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.service.BookingLogService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.FeedbackLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    @MockBean
    private BookingLogService bookingLogService;
    @MockBean
    private BookingCancellationService bookingCancellationService;

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
    @Test
    void agentCanLogABookingWithItsVoucherAsMultipart() throws Exception {
        when(bookingLogService.log(any(), any())).thenReturn(BookingResponse.builder().id("B9").build());

        mockMvc.perform(multipart("/api/bookings/log")
                        .file(logPart())
                        .file(new MockMultipartFile("file", "ticket.pdf", "application/pdf", new byte[]{1, 2, 3}))
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void accountantCannotLogABooking() throws Exception {
        mockMvc.perform(multipart("/api/bookings/log")
                        .file(logPart())
                        .file(new MockMultipartFile("file", "ticket.pdf", "application/pdf", new byte[]{1}))
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isForbidden());
    }

    private MockMultipartFile logPart() throws Exception {
        BookingLogRequest request = new BookingLogRequest();
        request.setServiceId("S1");
        request.setReference("ABCXYZ");
        request.setNetCost(java.math.BigDecimal.valueOf(42000));
        request.setSellingPrice(java.math.BigDecimal.valueOf(52000));
        return new MockMultipartFile("booking", "", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(request));
    }

    @Test
    void agentCannotMarkARefundPaidButAnAccountantCan() throws Exception {
        String body = "{\"amount\":100,\"paidOn\":\"2026-10-16\",\"paymentMode\":\"UPI\"}";
        mockMvc.perform(post("/api/bookings/B1/refunds/customer")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        when(bookingCancellationService.payCustomer(any(), any())).thenReturn(BookingResponse.builder().id("B1").build());
        mockMvc.perform(post("/api/bookings/B1/refunds/customer")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void agentCannotCrossCheckARefundButAnAccountantCan() throws Exception {
        mockMvc.perform(post("/api/bookings/B1/refunds/check")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT")))
                .andExpect(status().isForbidden());
        when(bookingCancellationService.markChecked(any())).thenReturn(BookingResponse.builder().id("B1").build());
        mockMvc.perform(post("/api/bookings/B1/refunds/check")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT")))
                .andExpect(status().isOk());
    }

    @Test
    void theAgentRecordsTheSupplierRefundAndTheAccountantConfirmsIt() throws Exception {
        String body = "{\"amount\":100,\"paidOn\":\"2026-10-18\"}";
        when(bookingCancellationService.recordSupplierRefund(any(), any())).thenReturn(BookingResponse.builder().id("B1").build());
        when(bookingCancellationService.confirmSupplierRefund(any(), any())).thenReturn(BookingResponse.builder().id("B1").build());
        mockMvc.perform(post("/api/bookings/B1/refunds/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/bookings/B1/refunds/supplier")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        for (String path : new String[]{"/confirm", "/reject"}) {
            mockMvc.perform(post("/api/bookings/B1/refunds/supplier" + path)
                            .with(SecurityMockMvcRequestPostProcessors.user("agent1").roles("AGENT"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/bookings/B1/refunds/supplier/confirm")
                        .with(SecurityMockMvcRequestPostProcessors.user("accountant1").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
