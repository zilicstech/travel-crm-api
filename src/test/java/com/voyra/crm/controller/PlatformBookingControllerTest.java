package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformBookingResponse;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.booking.CancelPreview;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformBookingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformBookingControllerTest {

    private static final String CONFIRM = """
            {"idempotencyKey":"key-12345678","acceptedDisplayedFare":4500.00,"contactEmail":"a@b.test","contactPhone":"9876543210",
             "passengers":[{"type":"ADULT","firstName":"Rahul","lastName":"Sharma"}]}""";

    @Autowired private MockMvc mockMvc;
    @MockBean private PlatformBookingService service;
    @MockBean private PlatformBookingQueryService queryService;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "u", type, "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    @Test
    void ownerAndAgentReview() throws Exception {
        given(service.review(any())).willReturn(PlatformBookingReviewResponse.builder().bookingId("B1")
                .displayedFare(new BigDecimal("4500.00")).build());
        for (UserType type : List.of(UserType.AGENCY_OWNER, UserType.AGENT)) {
            mockMvc.perform(post("/api/platform-bookings/flights/review").with(as(type))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"o1\",\"adults\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").value("B1"));
        }
    }

    @Test
    void accountantMayOnlyRead() throws Exception {
        mockMvc.perform(post("/api/platform-bookings/flights/review").with(as(UserType.ACCOUNTANT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"o1\",\"adults\":1}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform-bookings/B1/confirm").with(as(UserType.ACCOUNTANT))
                .contentType(MediaType.APPLICATION_JSON).content(CONFIRM)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform-bookings/B1/abort").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform-bookings/B1/cancel").with(as(UserType.ACCOUNTANT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"key-12345678\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);

        given(queryService.list(any(), any())).willReturn(PagedResponse.<PlatformBookingResponse>builder().content(List.of()).build());
        mockMvc.perform(get("/api/platform-bookings").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
    }

    @Test
    void superAdminHasNoAgencyBookings() throws Exception {
        mockMvc.perform(get("/api/platform-bookings").with(as(UserType.SUPER_ADMIN))).andExpect(status().isForbidden());
    }

    @Test
    void confirmReturnsTheAgencyViewAndConflictsKeepTheirCode() throws Exception {
        PlatformBooking row = PlatformBooking.builder().id("B1").status(PlatformBookingStatus.TICKETED).build();
        given(service.confirm(eq("B1"), any())).willReturn(row);
        given(queryService.view(row)).willReturn(PlatformBookingResponse.builder().id("B1").status(PlatformBookingStatus.TICKETED).build());

        mockMvc.perform(post("/api/platform-bookings/B1/confirm").with(as(UserType.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TICKETED"));

        given(service.confirm(eq("B2"), any())).willThrow(new BookingConflictException("PRICE_CHANGED", "changed", Map.of()));
        mockMvc.perform(post("/api/platform-bookings/B2/confirm").with(as(UserType.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
    }

    @Test
    void agencyJsonNeverCarriesVoyraMoneyOrSupplierIds() throws Exception {
        given(queryService.get("B1")).willReturn(PlatformBookingResponse.builder().id("B1").status(PlatformBookingStatus.TICKETED)
                .pnr("ABC123").displayedFare(new BigDecimal("4500.00")).agencyEarns(new BigDecimal("95.00")).build());

        String json = mockMvc.perform(get("/api/platform-bookings/B1").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        for (String key : List.of("voyraEarned", "voyraNet", "gross", "supplierReviewId", "supplierBookingId", "agencyCommission", "tds")) {
            org.assertj.core.api.Assertions.assertThat(json).as(key).doesNotContain(key);
        }
    }

    @Test
    void anotherAgencysBookingIs404() throws Exception {
        given(queryService.get("OTHER")).willThrow(new NotFoundException("Booking not found"));
        mockMvc.perform(get("/api/platform-bookings/OTHER").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNotFound());
    }

    @Test
    void cancelQuoteAndCancel() throws Exception {
        given(service.cancelQuote("B1")).willReturn(new CancelPreview(null, new BigDecimal("95.00"), null, BigDecimal.ZERO, true, null, null));
        mockMvc.perform(get("/api/platform-bookings/B1/cancel-quote").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundToBeConfirmed").value(true));

        PlatformBooking row = PlatformBooking.builder().id("B1").status(PlatformBookingStatus.CANCELLED).build();
        given(service.cancel("B1", "key-12345678")).willReturn(row);
        given(queryService.view(row)).willReturn(PlatformBookingResponse.builder().id("B1").status(PlatformBookingStatus.CANCELLED).build());
        mockMvc.perform(post("/api/platform-bookings/B1/cancel").with(as(UserType.AGENCY_OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"key-12345678\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(service).cancel("B1", "key-12345678");
    }

    @Test
    void abortReturnsTheAbortedBooking() throws Exception {
        PlatformBooking row = PlatformBooking.builder().id("B1").status(PlatformBookingStatus.ABORTED).build();
        given(service.abort("B1")).willReturn(row);
        given(queryService.view(row)).willReturn(PlatformBookingResponse.builder().id("B1").status(PlatformBookingStatus.ABORTED).build());
        mockMvc.perform(post("/api/platform-bookings/B1/abort").with(as(UserType.AGENT)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ABORTED")));
    }

    private static final String HOTEL_REVIEW = "{\"hotelId\":\"H1\",\"optionId\":\"O1\",\"checkIn\":\"2026-12-01\",\"checkOut\":\"2026-12-03\","
            + "\"rooms\":[{\"adults\":2,\"childAges\":[6]}],\"hotelName\":\"Sea Breeze Resort\"}";

    @Test
    void hotelReviewReturnsTheReviewWithItsPolicy() throws Exception {
        given(service.reviewHotel(any())).willReturn(PlatformBookingReviewResponse.builder().bookingId("B2")
                .displayedFare(new BigDecimal("4500.00")).cancellationPolicy("Free cancellation until 2026-11-29").build());

        mockMvc.perform(post("/api/platform-bookings/hotels/review").with(as(UserType.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content(HOTEL_REVIEW))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").value("B2"))
                .andExpect(jsonPath("$.cancellationPolicy").value("Free cancellation until 2026-11-29"));
        verify(service).reviewHotel(argThat(r -> r.getRooms().size() == 1 && r.getRooms().get(0).getChildAges().equals(List.of(6))));
    }

    @Test
    void hotelReviewRefusesNoRoomsTheAccountantAndAnAgentWithoutHotels() throws Exception {
        mockMvc.perform(post("/api/platform-bookings/hotels/review").with(as(UserType.AGENT)).contentType(MediaType.APPLICATION_JSON)
                .content(HOTEL_REVIEW.replace("[{\"adults\":2,\"childAges\":[6]}]", "[]"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/platform-bookings/hotels/review").with(as(UserType.ACCOUNTANT))
                .contentType(MediaType.APPLICATION_JSON).content(HOTEL_REVIEW)).andExpect(status().isForbidden());
        verifyNoInteractions(service);

        given(service.reviewHotel(any())).willThrow(new org.springframework.security.access.AccessDeniedException("hotels"));
        mockMvc.perform(post("/api/platform-bookings/hotels/review").with(as(UserType.AGENT))
                .contentType(MediaType.APPLICATION_JSON).content(HOTEL_REVIEW)).andExpect(status().isForbidden());
    }
}
