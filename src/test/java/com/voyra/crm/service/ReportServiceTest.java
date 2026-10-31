package com.voyra.crm.service;

import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.CategoryCountResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.MonthlyRevenuePoint;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock LeadRepository leadRepository;
    @Mock BookingRepository bookingRepository;
    @Mock LeadService leadService;
    @Mock BookingService bookingService;
    @Mock AgentService agentService;
    @InjectMocks ReportService service;

    private static BookingRepository.MonthlyRevenueProjection month(YearMonth ym, String revenue, String profit) {
        return new BookingRepository.MonthlyRevenueProjection() {
            public int getYear() { return ym.getYear(); }
            public int getMonth() { return ym.getMonthValue(); }
            public BigDecimal getRevenue() { return revenue == null ? null : new BigDecimal(revenue); }
            public BigDecimal getProfit() { return profit == null ? null : new BigDecimal(profit); }
        };
    }

    private static LeadRepository.CategoryCountProjection count(String category, long n) {
        return new LeadRepository.CategoryCountProjection() {
            public String getCategory() { return category; }
            public long getCount() { return n; }
        };
    }

    @Test
    void revenueTrendFillsEveryMonthAndIgnoresRowsOutsideTheWindow() {
        YearMonth now = YearMonth.now();
        when(bookingRepository.aggregateMonthlyRevenueSince(now.minusMonths(2).atDay(1))).thenReturn(List.of(
                month(now, "1000.00", "200.00"),
                month(now.minusMonths(1), null, null),
                month(now.minusMonths(7), "5.00", "1.00")));

        List<MonthlyRevenuePoint> trend = service.getRevenueTrend(3);

        assertThat(trend).extracting(MonthlyRevenuePoint::getMonth).containsExactly(
                now.minusMonths(2).toString(), now.minusMonths(1).toString(), now.toString());
        assertThat(trend.get(0).getRevenue()).isEqualByComparingTo("0");
        assertThat(trend.get(1).getRevenue()).isEqualByComparingTo("0");
        assertThat(trend.get(1).getProfit()).isEqualByComparingTo("0");
        assertThat(trend.get(2).getRevenue()).isEqualByComparingTo("1000.00");
        assertThat(trend.get(2).getProfit()).isEqualByComparingTo("200.00");
    }

    @Test
    void leadPipelineCountsEveryStatus() {
        when(leadRepository.countByStatus(any())).thenReturn(0L);
        when(leadRepository.countByStatus(LeadStatus.NEW)).thenReturn(4L);

        List<CategoryCountResponse> pipeline = service.getLeadPipeline();

        assertThat(pipeline).hasSize(LeadStatus.values().length);
        assertThat(pipeline.get(0).getCategory()).isEqualTo(LeadStatus.values()[0].name());
        assertThat(pipeline).filteredOn(c -> c.getCategory().equals("NEW"))
                .extracting(CategoryCountResponse::getCount).containsExactly(4L);
    }

    @Test
    void sourceAndTypeDistributionsMapProjections() {
        when(leadRepository.countGroupedBySource()).thenReturn(List.of(count("Referral", 3)));
        when(bookingRepository.countGroupedByType()).thenReturn(List.of(count("FLIGHT", 9)));

        assertThat(service.getLeadSourceDistribution()).singleElement()
                .satisfies(c -> { assertThat(c.getCategory()).isEqualTo("Referral"); assertThat(c.getCount()).isEqualTo(3L); });
        assertThat(service.getBookingTypeDistribution()).singleElement()
                .satisfies(c -> { assertThat(c.getCategory()).isEqualTo("FLIGHT"); assertThat(c.getCount()).isEqualTo(9L); });
    }

    @Test
    void conversionFunnelIsCumulativeFromTheLastStage() {
        when(leadRepository.countGroupedByStatusExcluding(LeadStatus.LOST)).thenReturn(List.of(
                count("NEW", 5), count("QUALIFIED", 2), count("BOOKED", 1)));

        List<CategoryCountResponse> funnel = service.getConversionFunnel();

        assertThat(funnel).extracting(CategoryCountResponse::getCategory).containsExactly(
                "NEW", "CONTACTED", "QUALIFIED", "PROPOSAL_SENT", "NEGOTIATING", "BOOKED");
        assertThat(funnel).extracting(CategoryCountResponse::getCount).containsExactly(8L, 3L, 3L, 1L, 1L, 1L);
    }

    private LeadResponse lead() {
        return LeadResponse.builder().id("L1").clientName("Asha, Rao").destination("Goa").status(LeadStatus.NEW)
                .source("Referral").priority(LeadPriority.values()[0]).totalTravellers(2).createdByName("Agent A")
                .followUpDate(LocalDate.of(2026, 11, 2)).createdAt(LocalDateTime.of(2026, 10, 1, 9, 30)).build();
    }

    @Test
    void leadsExportWritesOneRowPerLeadInEveryFormat() {
        LeadResponse sparse = lead();
        sparse.setTotalTravellers(null);
        sparse.setFollowUpDate(null);
        when(leadService.listLeads(null)).thenReturn(List.of(lead(), sparse));

        String csv = service.exportLeadsCsv();

        assertThat(csv.split("\n")).containsExactly(
                "id,clientName,destination,status,source,priority,totalTravellers,createdByName,followUpDate,createdAt",
                "L1,\"Asha, Rao\",Goa,NEW,Referral," + LeadPriority.values()[0].name() + ",2,Agent A,2026-11-02,2026-10-01T09:30",
                "L1,\"Asha, Rao\",Goa,NEW,Referral," + LeadPriority.values()[0].name() + ",,Agent A,,2026-10-01T09:30");
        assertThat(service.exportLeadsXlsx()).startsWith('P', 'K');
        assertThat(new String(service.exportLeadsPdf(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void bookingsExportWritesMoneyColumns() {
        BookingResponse b = BookingResponse.builder().id("B1").type(BookingType.FLIGHT).clientName("C").destination("DXB")
                .agentName("Agent A").netCost(new BigDecimal("900.00")).sellingPrice(new BigDecimal("1000.00"))
                .profit(new BigDecimal("100.00")).bookingStatus(BookingStatus.values()[0])
                .paymentStatus(PaymentStatus.values()[0]).bookingDate(LocalDate.of(2026, 10, 3)).build();
        when(bookingService.listBookings(null, null)).thenReturn(List.of(b));

        assertThat(service.exportBookingsCsv()).endsWith("B1,FLIGHT,C,DXB,Agent A,900.00,1000.00,100.00,"
                + BookingStatus.values()[0].name() + "," + PaymentStatus.values()[0].name() + ",2026-10-03\n");
        assertThat(service.exportBookingsXlsx()).startsWith('P', 'K');
        assertThat(new String(service.exportBookingsPdf(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void revenueExportUsesTheTrend() {
        when(bookingRepository.aggregateMonthlyRevenueSince(YearMonth.now().atDay(1)))
                .thenReturn(List.of(month(YearMonth.now(), "50.00", "5.00")));

        assertThat(service.exportRevenueCsv(1)).isEqualTo("month,revenue,profit\n" + YearMonth.now() + ",50.00,5.00\n");
        assertThat(service.exportRevenueXlsx(1)).startsWith('P', 'K');
        assertThat(new String(service.exportRevenuePdf(1), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void agentsExportLeavesAMissingDepartmentBlank() {
        AgentPerformanceResponse withDept = AgentPerformanceResponse.builder().id("A1").name("Agent A")
                .department(AgentDepartment.values()[0]).leadsAssigned(4L).bookingsCount(2L)
                .revenueGenerated(new BigDecimal("2000.00")).conversionPercent(50).commission(new BigDecimal("40.00")).build();
        AgentPerformanceResponse noDept = AgentPerformanceResponse.builder().id("A2").name("Agent B").build();
        when(agentService.listAgents()).thenReturn(List.of(withDept, noDept));

        assertThat(service.exportAgentsCsv().split("\n")).containsExactly(
                "id,name,department,leadsAssigned,bookingsCount,revenueGenerated,conversionPercent,commission",
                "A1,Agent A," + AgentDepartment.values()[0].name() + ",4,2,2000.00,50,40.00",
                "A2,Agent B,,,,,,");
        assertThat(service.exportAgentsXlsx()).startsWith('P', 'K');
        assertThat(new String(service.exportAgentsPdf(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
