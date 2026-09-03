package com.voyra.crm.service;

import com.voyra.crm.dto.AgentDashboardSummaryResponse;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.OwnerDashboardSummaryResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingRepository.AgentRevenueProjection;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);

    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final ClientRepository clientRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;

    @Transactional(readOnly = true)
    public OwnerDashboardSummaryResponse getOwnerSummary() {
        AgentRevenueProjection revenue = bookingRepository.sumRevenueForAgency();
        long totalLeads = leadRepository.count();
        long bookedLeads = leadRepository.countByStatus(LeadStatus.BOOKED);
        long lostLeads = leadRepository.countByStatus(LeadStatus.LOST);

        BigDecimal pendingPayments = clientInvoiceRepository.sumOutstanding(InvoiceStatus.PAID);

        return OwnerDashboardSummaryResponse.builder()
                .totalRevenue(revenue.getTotalRevenue())
                .totalProfit(revenue.getTotalProfit())
                .totalNetCost(revenue.getTotalNetCost())
                .activeClients(clientRepository.countByIsActiveTrue())
                .totalLeads(totalLeads)
                .bookedLeads(bookedLeads)
                .lostLeads(lostLeads)
                .conversionRate(percentage(bookedLeads, totalLeads))
                .pendingPayments(pendingPayments)
                .overdueFollowUps(leadRepository.countByFollowUpDateLessThanEqualAndStatusNotIn(
                        LocalDate.now(), TERMINAL_STATUSES))
                .pendingBookings(bookingRepository.countByBookingStatus(BookingStatus.PENDING))
                .confirmedBookings(bookingRepository.countByBookingStatus(BookingStatus.CONFIRMED))
                .totalBookings(bookingRepository.count())
                .build();
    }

    @Transactional(readOnly = true)
    public AgentDashboardSummaryResponse getAgentSummary() {
        String agentId = SecurityContextUtil.getCurrentUserOrThrow().userId();

        long myLeadsCount = leadRepository.countByAssignedTo(agentId);
        long bookedCount = leadRepository.countByAssignedToAndStatus(agentId, LeadStatus.BOOKED);
        AgentRevenueProjection revenue = bookingRepository.sumRevenueByAgentId(agentId);

        List<BookingResponse> recentBookings = bookingRepository.findByAgentId(agentId).stream()
                .sorted(Comparator.comparing(Booking::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(this::toBookingResponse)
                .toList();

        List<LeadResponse> overdueFollowUps = leadRepository.findByAssignedTo(agentId).stream()
                .filter(this::isOverdue)
                .map(this::toLeadResponse)
                .toList();

        return AgentDashboardSummaryResponse.builder()
                .myLeadsCount(myLeadsCount)
                .newLeadsCount(leadRepository.countByAssignedToAndStatus(agentId, LeadStatus.NEW))
                .todayFollowUpsCount(leadRepository.countByAssignedToAndFollowUpDateLessThanEqualAndStatusNotIn(
                        agentId, LocalDate.now(), TERMINAL_STATUSES))
                .bookingsCount(bookingRepository.countByAgentId(agentId))
                .pendingBookingsCount(bookingRepository.countByAgentIdAndBookingStatus(agentId, BookingStatus.PENDING))
                .revenue(revenue.getTotalRevenue())
                .profit(revenue.getTotalProfit())
                .conversionPercent(percentage(bookedCount, myLeadsCount))
                .recentBookings(recentBookings)
                .overdueFollowUps(overdueFollowUps)
                .build();
    }

    private boolean isOverdue(Lead lead) {
        return lead.getFollowUpDate() != null
                && lead.getFollowUpDate().isBefore(LocalDate.now())
                && !TERMINAL_STATUSES.contains(lead.getStatus());
    }

    private int percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private BookingResponse toBookingResponse(Booking b) {
        return BookingResponse.builder()
                .id(b.getId()).clientId(b.getClientId()).clientName(b.getClientName())
                .agentId(b.getAgentId()).agentName(b.getAgentName()).type(b.getType())
                .destination(b.getDestination()).pnr(b.getPnr()).ticketNo(b.getTicketNo())
                .airline(b.getAirline()).supplier(b.getSupplier()).journeyDate(b.getJourneyDate())
                .returnDate(b.getReturnDate()).tripType(b.getTripType()).netCost(b.getNetCost())
                .sellingPrice(b.getSellingPrice()).profit(b.getProfit()).bookingStatus(b.getBookingStatus())
                .paymentStatus(b.getPaymentStatus()).bookingDate(b.getBookingDate())
                .cancelReason(b.getCancelReason()).refundStatus(b.getRefundStatus())
                .createdDate(b.getCreatedDate())
                .build();
    }

    private LeadResponse toLeadResponse(Lead lead) {
        return LeadResponse.builder()
                .id(lead.getId()).clientId(lead.getClientId()).clientName(lead.getClientName())
                .clientType(lead.getClientType()).destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom()).travelDateTo(lead.getTravelDateTo())
                .categories(lead.getCategories()).budget(lead.getBudget()).status(lead.getStatus())
                .source(lead.getSource()).priority(lead.getPriority()).assignedTo(lead.getAssignedTo())
                .totalTravellers(lead.getTotalTravellers())
                .assignedAgentName(lead.getAssignedAgentName()).followUpDate(lead.getFollowUpDate())
                .createdAt(lead.getCreatedAt()).overdue(true)
                .build();
    }
}
