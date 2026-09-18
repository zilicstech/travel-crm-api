package com.voyra.crm.service;

import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.CategoryCountResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.MonthlyRevenuePoint;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.util.CsvWriter;
import com.voyra.crm.util.PdfTableWriter;
import com.voyra.crm.util.ReportTable;
import com.voyra.crm.util.XlsxWriter;
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
        YearMonth start = YearMonth.now().minusMonths(months - 1L);

        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            byMonth.put(start.plusMonths(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (var row : bookingRepository.aggregateMonthlyRevenueSince(start.atDay(1))) {
            YearMonth ym = YearMonth.of(row.getYear(), row.getMonth());
            BigDecimal[] agg = byMonth.get(ym);
            if (agg == null) {
                continue; // outside the requested window
            }
            agg[0] = row.getRevenue() != null ? row.getRevenue() : BigDecimal.ZERO;
            agg[1] = row.getProfit() != null ? row.getProfit() : BigDecimal.ZERO;
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
        return leadRepository.countGroupedBySource().stream()
                .map(p -> CategoryCountResponse.builder().category(p.getCategory()).count(p.getCount()).build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getBookingTypeDistribution() {
        return bookingRepository.countGroupedByType().stream()
                .map(p -> CategoryCountResponse.builder().category(p.getCategory()).count(p.getCount()).build())
                .toList();
    }

    /** Cumulative "reached this stage or beyond" counts (excluding Lost), so each stage <= the previous one. */
    @Transactional(readOnly = true)
    public List<CategoryCountResponse> getConversionFunnel() {
        Map<LeadStatus, Long> countsByStatus = leadRepository.countGroupedByStatusExcluding(LeadStatus.LOST).stream()
                .collect(java.util.stream.Collectors.toMap(p -> LeadStatus.valueOf(p.getCategory()), p -> p.getCount()));

        long[] cumulative = new long[FUNNEL_STAGES.size()];
        for (int i = FUNNEL_STAGES.size() - 1; i >= 0; i--) {
            long atThisStage = countsByStatus.getOrDefault(FUNNEL_STAGES.get(i), 0L);
            cumulative[i] = atThisStage + (i + 1 < FUNNEL_STAGES.size() ? cumulative[i + 1] : 0L);
        }

        return java.util.stream.IntStream.range(0, FUNNEL_STAGES.size())
                .mapToObj(i -> CategoryCountResponse.builder()
                        .category(FUNNEL_STAGES.get(i).name())
                        .count(cumulative[i])
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public String exportLeadsCsv() {
        ReportTable table = leadsTable();
        return CsvWriter.write(table.header(), table.rows());
    }

    @Transactional(readOnly = true)
    public byte[] exportLeadsXlsx() {
        return XlsxWriter.write("Leads", leadsTable());
    }

    @Transactional(readOnly = true)
    public byte[] exportLeadsPdf() {
        return PdfTableWriter.write("Leads", leadsTable());
    }

    @Transactional(readOnly = true)
    public String exportBookingsCsv() {
        ReportTable table = bookingsTable();
        return CsvWriter.write(table.header(), table.rows());
    }

    @Transactional(readOnly = true)
    public byte[] exportBookingsXlsx() {
        return XlsxWriter.write("Bookings", bookingsTable());
    }

    @Transactional(readOnly = true)
    public byte[] exportBookingsPdf() {
        return PdfTableWriter.write("Bookings", bookingsTable());
    }

    @Transactional(readOnly = true)
    public String exportRevenueCsv(int months) {
        ReportTable table = revenueTable(months);
        return CsvWriter.write(table.header(), table.rows());
    }

    @Transactional(readOnly = true)
    public byte[] exportRevenueXlsx(int months) {
        return XlsxWriter.write("Revenue", revenueTable(months));
    }

    @Transactional(readOnly = true)
    public byte[] exportRevenuePdf(int months) {
        return PdfTableWriter.write("Revenue", revenueTable(months));
    }

    @Transactional(readOnly = true)
    public String exportAgentsCsv() {
        ReportTable table = agentsTable();
        return CsvWriter.write(table.header(), table.rows());
    }

    @Transactional(readOnly = true)
    public byte[] exportAgentsXlsx() {
        return XlsxWriter.write("Agents", agentsTable());
    }

    @Transactional(readOnly = true)
    public byte[] exportAgentsPdf() {
        return PdfTableWriter.write("Agents", agentsTable());
    }

    private ReportTable leadsTable() {
        List<LeadResponse> leads = leadService.listLeads(null);
        List<String> header = List.of("id", "clientName", "destination", "status", "source", "priority",
                "totalTravellers", "createdByName", "followUpDate", "createdAt");
        List<List<String>> rows = leads.stream()
                .map(l -> List.of(l.getId(), l.getClientName(), l.getDestination(),
                        l.getStatus().name(), l.getSource(), l.getPriority().name(),
                        str(l.getTotalTravellers()),
                        l.getCreatedByName(), str(l.getFollowUpDate()), str(l.getCreatedAt())))
                .toList();
        return new ReportTable(header, rows);
    }

    private ReportTable bookingsTable() {
        List<BookingResponse> bookings = bookingService.listBookings(null, null);
        List<String> header = List.of("id", "type", "clientName", "destination", "agentName",
                "netCost", "sellingPrice", "profit", "bookingStatus", "paymentStatus", "bookingDate");
        List<List<String>> rows = bookings.stream()
                .map(b -> List.of(b.getId(), b.getType().name(), b.getClientName(), b.getDestination(),
                        b.getAgentName(), str(b.getNetCost()), str(b.getSellingPrice()), str(b.getProfit()),
                        b.getBookingStatus().name(), b.getPaymentStatus().name(), str(b.getBookingDate())))
                .toList();
        return new ReportTable(header, rows);
    }

    private ReportTable revenueTable(int months) {
        List<String> header = List.of("month", "revenue", "profit");
        List<List<String>> rows = getRevenueTrend(months).stream()
                .map(p -> List.of(p.getMonth(), str(p.getRevenue()), str(p.getProfit())))
                .toList();
        return new ReportTable(header, rows);
    }

    private ReportTable agentsTable() {
        List<AgentPerformanceResponse> agents = agentService.listAgents();
        List<String> header = List.of("id", "name", "department", "leadsAssigned", "bookingsCount",
                "revenueGenerated", "conversionPercent", "commission");
        List<List<String>> rows = agents.stream()
                .map(a -> List.of(a.getId(), a.getName(), a.getDepartment().name(), str(a.getLeadsAssigned()),
                        str(a.getBookingsCount()), str(a.getRevenueGenerated()), str(a.getConversionPercent()),
                        str(a.getCommission())))
                .toList();
        return new ReportTable(header, rows);
    }

    private String str(Object value) {
        return value != null ? value.toString() : "";
    }
}
