package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** profit = sellingPrice - netCost, always recomputed server-side - never trusted from a stored or client value. */
@ExtendWith(MockitoExtension.class)
class BookingProfitTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void authenticateAsAgent() {
        CustomUserPrincipal principal = new CustomUserPrincipal("A1", "liam", UserType.AGENT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void profitIsSellingPriceMinusNetCostOnCreate() {
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Liam").build()));
        when(clientRepository.findById("K1")).thenReturn(Optional.of(Client.builder().id("K1").name("Jane").build()));
        when(bookingRepository.existsById(anyString())).thenReturn(false);

        BookingCreateRequest request = new BookingCreateRequest();
        request.setClientId("K1");
        request.setType(BookingType.FLIGHT);
        request.setDestination("Dubai");
        request.setNetCost(new BigDecimal("42000"));
        request.setSellingPrice(new BigDecimal("52000"));

        BookingResponse response = bookingService.createBooking(request);

        assertThat(response.getProfit()).isEqualByComparingTo("10000");
    }

    @Test
    void profitIsRecomputedOnUpdateWhenCostChanges() {
        Booking booking = Booking.builder()
                .id("P1").agentId("A1").clientId("K1")
                .netCost(new BigDecimal("42000")).sellingPrice(new BigDecimal("52000"))
                .profit(new BigDecimal("10000"))
                .build();
        when(bookingRepository.findById("P1")).thenReturn(Optional.of(booking));

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setNetCost(new BigDecimal("40000"));

        BookingResponse response = bookingService.updateBooking("P1", request);

        assertThat(response.getProfit()).isEqualByComparingTo("12000");
    }

    @Test
    void profitIsRecomputedFromCurrentValuesNotTrustedFromStoredStaleValue() {
        // The stored profit (999999) is deliberately wrong, simulating stale/corrupted data.
        // The update touches only pnr - the service must still recompute profit from the
        // booking's actual current cost fields, never carry the stale stored value forward.
        Booking booking = Booking.builder()
                .id("P2").agentId("A1").clientId("K1")
                .netCost(new BigDecimal("42000")).sellingPrice(new BigDecimal("52000"))
                .profit(new BigDecimal("999999"))
                .bookingStatus(BookingStatus.PENDING)
                .build();
        when(bookingRepository.findById("P2")).thenReturn(Optional.of(booking));

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setPnr("XYZ123");

        BookingResponse response = bookingService.updateBooking("P2", request);

        assertThat(response.getProfit()).isEqualByComparingTo("10000");
    }
}
