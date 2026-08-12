package com.voyra.crm.service;

import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.CategoryCountResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.MonthlyRevenuePoint;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final List<LeadStatus> FUNNEL_STAGES = List.of(
            LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUALIFIED,
            LeadStatus.PROPOSAL_SENT, LeadStatus.NEGOTIATING, LeadStatus.BOOKED);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final LeadService leadService;
    private final BookingService bookingService;
    private final AgentService agentService;

    @Transactional(readOnly = true)
    public List<MonthlyRevenuePoint> getRevenueTrend(int months) {
        List<Booking> bookings = bookingRepository.findAll();
        YearMonth start = YearMonth.now().minusMonths(months - 1L);

        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            byMonth.put(start.plusMonths(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (Booking b : bookings) {
            if (b.getBookingDate() == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(b.getBookingDate());
            BigDecimal[] agg = byMonth.get(ym);
            if (agg == null) {
                continue; // outside the requested window
            }
            agg[0] = agg[0].add(b.getSellingPrice() != null ? b.getSellingPrice() : BigDecimal.ZERO);
            agg[1] = agg[1].add(b.getProfit() != null ? b.getProfit() : BigDecimal.ZERO);
        }

        return byMonth.entrySet().stream()
                .map(e -> MonthlyRevenuePoint.builder()
                        .month(e.getKey().format(MONTH_FORMAT))
                        .revenue(e.getValue()[0])
                        .profit(e.getValue()[1])
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getLeadPipeline() {
        return List.of(LeadStatus.values()).stream()
                .map(status -> CategoryCountResponse.builder()
                        .category(status.name())
                        .count(leadRepository.countByStatus(status))
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getLeadSourceDistribution() {
        return countBy(leadRepository.findAll(), l -> l.getSource().name());
    }

    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getBookingTypeDistribution() {
        return countBy(bookingRepository.findAll(), b -> b.getType().name());
    }

    /** Cumulative "reached this stage or beyond" counts (excluding Lost), so each stage <= the previous one. */
    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getConversionFunnel() {
        List<Lead> leads = leadRepository.findAll().stream()
                .filter(l -> l.getStatus() != LeadStatus.LOST)
                .toList();

        return FUNNEL_STAGES.stream()
                .map(stage -> {
                    int stageRank = FUNNEL_STAGES.indexOf(stage);
                    long count = leads.stream()
                            .filter(l -> FUNNEL_STAGES.indexOf(l.getStatus()) >= stageRank)
                            .count();
                    return CategoryCountResponse.builder().category(stage.name()).count(count).build();
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public String exportLeadsCsv() {
        List<LeadResponse> leads = leadService.listLeads(null);
        List<String> header = List.of("id", "name", "phone", "destination", "status", "source", "priority",
                "assignedAgentName", "followUpDate", "createdDate");
        List<List<String>> rows = leads.stream()
                .map(l -> List.of(l.getId(), l.getName(), l.getPhone(), l.getDestination(),
                        l.getStatus().name(), l.getSource().name(), l.getPriority().name(),
                        l.getAssignedAgentName(), str(l.getFollowUpDate()), str(l.getCreatedDate())))
                .toList();
        return CsvWriter.write(header, rows);
    }

    @Transactional(readOnly = true)
    public String exportBookingsCsv() {
        List<BookingResponse> bookings = bookingService.listBookings(null, null);
        List<String> header = List.of("id", "type", "customerName", "destination", "agentName",
                "netCost", "sellingPrice", "profit", "bookingStatus", "paymentStatus", "bookingDate");
        List<List<String>> rows = bookings.stream()
                .map(b -> List.of(b.getId(), b.getType().name(), b.getCustomerName(), b.getDestination(),
                        b.getAgentName(), str(b.getNetCost()), str(b.getSellingPrice()), str(b.getProfit()),
                        b.getBookingStatus().name(), b.getPaymentStatus().name(), str(b.getBookingDate())))
                .toList();
        return CsvWriter.write(header, rows);
    }

    @Transactional(readOnly = true)
    public String exportRevenueCsv(int months) {
        List<String> header = List.of("month", "revenue", "profit");
        List<List<String>> rows = getRevenueTrend(months).stream()
                .map(p -> List.of(p.getMonth(), str(p.getRevenue()), str(p.getProfit())))
                .toList();
        return CsvWriter.write(header, rows);
    }

    @Transactional(readOnly = true)
    public String exportAgentsCsv() {
        List<AgentPerformanceResponse> agents = agentService.listAgents();
        List<String> header = List.of("id", "name", "department", "leadsAssigned", "bookingsCount",
                "revenueGenerated", "conversionPercent", "commission");
        List<List<String>> rows = agents.stream()
                .map(a -> List.of(a.getId(), a.getName(), a.getDepartment().name(), str(a.getLeadsAssigned()),
                        str(a.getBookingsCount()), str(a.getRevenueGenerated()), str(a.getConversionPercent()),
                        str(a.getCommission())))
                .toList();
        return CsvWriter.write(header, rows);
    }

    private String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private <T> List<CategoryCountResponse> countBy(List<T> items, java.util.function.Function<T, String> keyFn) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (T item : items) {
            counts.merge(keyFn.apply(item), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> CategoryCountResponse.builder().category(e.getKey()).count(e.getValue()).build())
                .toList();
    }
}
