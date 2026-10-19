package com.voyra.crm.service;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.InvoiceRepository;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private ClientInvoiceRepository clientInvoiceRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private AgentRepository agentRepository;
    @InjectMocks private DashboardService dashboardService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Conversion means one thing everywhere (My Profile, agent detail): leads the agent created that
     * reached Booked, over leads they created - not over the wider set they can see via a service type.
     */
    @Test
    void agentConversionCountsOnlyLeadsTheAgentCreated() {
        CustomUserPrincipal principal = new CustomUserPrincipal("A1", "user", UserType.AGENT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        // Visible via service access: 20 leads, one Booked - would read as 5%.
        List<Lead> visible = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            visible.add(Lead.builder().id("L" + i).status(i == 0 ? LeadStatus.BOOKED : LeadStatus.NEW).build());
        }
        when(leadRepository.findAccessibleToAgentWithNoManagedTypes("A1")).thenReturn(visible);
        when(leadRepository.countByCreatedBy("A1")).thenReturn(0L);
        when(bookingRepository.sumRevenueByAgentId(any())).thenReturn(mock(BookingRepository.AgentRevenueProjection.class));

        assertThat(dashboardService.getAgentSummary().getConversionPercent()).isZero();

        when(leadRepository.countByCreatedBy("A1")).thenReturn(4L);
        when(leadRepository.countByCreatedByAndStatus("A1", LeadStatus.BOOKED)).thenReturn(1L);
        assertThat(dashboardService.getAgentSummary().getConversionPercent()).isEqualTo(25);
    }
}
