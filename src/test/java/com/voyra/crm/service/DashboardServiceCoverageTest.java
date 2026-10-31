package com.voyra.crm.service;

import com.voyra.crm.dto.AgentDashboardSummaryResponse;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.OwnerDashboardSummaryResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DashboardServiceCoverageTest {

    @Mock LeadRepository leadRepository;
    @Mock BookingRepository bookingRepository;
    @Mock ClientRepository clientRepository;
    @Mock ClientInvoiceRepository clientInvoiceRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock AgentRepository agentRepository;
    @Mock LeadFollowUpRepository leadFollowUpRepository;
    @InjectMocks DashboardService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BookingRepository.AgentRevenueProjection revenue(String total, String profit, String net) {
        return new BookingRepository.AgentRevenueProjection() {
            public BigDecimal getTotalRevenue() { return new BigDecimal(total); }
            public BigDecimal getTotalProfit() { return new BigDecimal(profit); }
            public BigDecimal getTotalNetCost() { return new BigDecimal(net); }
        };
    }

    @Test
    void theOwnerSummaryAddsBothInvoiceSourcesAndRoundsTheConversionRate() {
        when(bookingRepository.sumRevenueForAgency()).thenReturn(revenue("100000", "15000", "85000"));
        when(leadRepository.count()).thenReturn(3L);
        when(leadRepository.countByStatus(LeadStatus.BOOKED)).thenReturn(2L);
        when(leadRepository.countByStatus(LeadStatus.LOST)).thenReturn(1L);
        when(clientInvoiceRepository.sumOutstanding(InvoiceStatus.PAID)).thenReturn(new BigDecimal("1000"));
        when(invoiceRepository.sumOutstandingTaxInvoicesInr()).thenReturn(new BigDecimal("2500.50"));
        when(clientRepository.countByIsActiveTrue()).thenReturn(12L);
        when(bookingRepository.countByBookingStatus(BookingStatus.PENDING)).thenReturn(4L);
        when(bookingRepository.countByBookingStatus(BookingStatus.CONFIRMED)).thenReturn(6L);
        when(bookingRepository.count()).thenReturn(11L);

        OwnerDashboardSummaryResponse s = service.getOwnerSummary();

        assertThat(s.getTotalRevenue()).isEqualByComparingTo("100000");
        assertThat(s.getTotalProfit()).isEqualByComparingTo("15000");
        assertThat(s.getTotalNetCost()).isEqualByComparingTo("85000");
        assertThat(s.getPendingPayments()).isEqualByComparingTo("3500.50");
        assertThat(s.getConversionRate()).isEqualTo(67);
        assertThat(s.getActiveClients()).isEqualTo(12);
        assertThat(s.getLostLeads()).isEqualTo(1);
        assertThat(s.getPendingBookings()).isEqualTo(4);
        assertThat(s.getConfirmedBookings()).isEqualTo(6);
        assertThat(s.getTotalBookings()).isEqualTo(11);
    }

    @Test
    void anAgentWithServiceTypesSeesTodaysAndOverdueFollowUpsAndTheirFiveNewestBookings() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").manageableServices(List.of(ServiceType.VISA)).build()));
        LocalDate today = LocalDate.now();
        List<Lead> leads = List.of(
                Lead.builder().id("L1").status(LeadStatus.NEW).followUpDate(today).build(),
                Lead.builder().id("L2").status(LeadStatus.CONTACTED).followUpDate(today.minusDays(2)).clientName("Asha").build(),
                Lead.builder().id("L3").status(LeadStatus.NEW).followUpDate(today.plusDays(3)).build(),
                Lead.builder().id("L4").status(LeadStatus.BOOKED).followUpDate(today.minusDays(5)).build(),
                Lead.builder().id("L5").status(LeadStatus.QUALIFIED).build());
        when(leadRepository.findAccessibleToAgent("A1", List.of(ServiceType.VISA))).thenReturn(leads);
        when(bookingRepository.sumRevenueByAgentId("A1")).thenReturn(revenue("5000", "500", "4500"));
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(bookingRepository.findByAgentId("A1")).thenReturn(List.of(
                Booking.builder().id("B1").createdDate(t.plusDays(1)).build(),
                Booking.builder().id("B2").createdDate(null).build(),
                Booking.builder().id("B3").createdDate(t.plusDays(3)).build(),
                Booking.builder().id("B4").createdDate(t.plusDays(2)).build(),
                Booking.builder().id("B5").createdDate(t).build(),
                Booking.builder().id("B6").createdDate(t.plusDays(4)).bookingStatus(BookingStatus.CONFIRMED).build()));
        when(bookingRepository.countByAgentId("A1")).thenReturn(6L);
        when(bookingRepository.countByAgentIdAndBookingStatus("A1", BookingStatus.PENDING)).thenReturn(1L);

        AgentDashboardSummaryResponse s = service.getAgentSummary();

        assertThat(s.getMyLeadsCount()).isEqualTo(5);
        assertThat(s.getNewLeadsCount()).isEqualTo(2);
        assertThat(s.getTodayFollowUpsCount()).isEqualTo(2);
        assertThat(s.getOverdueFollowUps()).extracting(LeadResponse::getId).containsExactly("L2");
        assertThat(s.getOverdueFollowUps().get(0).isOverdue()).isTrue();
        assertThat(s.getOverdueFollowUps().get(0).getClientName()).isEqualTo("Asha");
        assertThat(s.getRecentBookings()).extracting(BookingResponse::getId).containsExactly("B6", "B3", "B4", "B1", "B5");
        assertThat(s.getRecentBookings().get(0).getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(s.getRevenue()).isEqualByComparingTo("5000");
        assertThat(s.getProfit()).isEqualByComparingTo("500");
        assertThat(s.getBookingsCount()).isEqualTo(6);
        assertThat(s.getPendingBookingsCount()).isEqualTo(1);
        verify(leadRepository, never()).findAccessibleToAgentWithNoManagedTypes(any());
    }
}
