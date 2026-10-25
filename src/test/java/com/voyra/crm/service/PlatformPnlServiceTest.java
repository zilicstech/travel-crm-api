package com.voyra.crm.service;

import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.dto.PnlRow;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformPnlServiceTest {

    @Mock
    private PlatformBookingRepository bookingRepository;
    @Mock
    private TenantRepository tenantRepository;
    @InjectMocks
    private PlatformPnlService service;

    private static PlatformBooking booking(String id, String tenant, PricingProduct product, String gross, String displayed,
                                           String earned, VoyraEarnedSource source, String commission, String tds, String net) {
        return PlatformBooking.builder().id(id).tenantId(tenant).product(product).status(PlatformBookingStatus.TICKETED)
                .environment(SupplierEnvironment.UAT).gross(new BigDecimal(gross)).displayed(new BigDecimal(displayed))
                .voyraEarned(new BigDecimal(earned)).voyraEarnedSource(source).agencyCommission(new BigDecimal(commission))
                .tds(new BigDecimal(tds)).voyraNet(new BigDecimal(net)).ticketedAt(Instant.parse("2026-10-05T06:00:00Z")).build();
    }

    private static Tenant tenant(String id, String name) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setAgencyName(name);
        return t;
    }

    private List<PlatformBooking> sample() {
        PlatformBooking f1 = booking("B1", "T1", PricingProduct.FLIGHT, "10000.00", "9500.00", "150.00",
                VoyraEarnedSource.ESTIMATE, "100.00", "2.00", "-450.00");
        PlatformBooking f2 = booking("B2", "T1", PricingProduct.FLIGHT, "5000.00", "4900.00", "180.00",
                VoyraEarnedSource.STATEMENT, "50.00", "1.00", "30.00");
        PlatformBooking h1 = booking("B3", "T2", PricingProduct.HOTEL, "8000.00", "7600.00", "400.00",
                VoyraEarnedSource.ESTIMATE, "200.00", "4.00", "-200.00").toBuilder()
                .status(PlatformBookingStatus.CANCELLED).voyraEarnedReversal(new BigDecimal("400.00")).voyraNet(new BigDecimal("-400.00")).build();
        return List.of(f1, f2, h1);
    }

    @Test
    void totalsAddUpEveryFigure() {
        given(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).willReturn(sample());
        given(tenantRepository.findAllById(any())).willReturn(List.of(tenant("T1", "Alpha Travels"), tenant("T2", "Beta Tours")));

        PnlRow t = service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null).getTotals();

        assertThat(t.getBookings()).isEqualTo(3);
        assertThat(t.getCancellations()).isEqualTo(1);
        assertThat(t.getGmv()).isEqualByComparingTo("22000.00");
        assertThat(t.getGross()).isEqualByComparingTo("23000.00");
        assertThat(t.getDiscountsGiven()).isEqualByComparingTo("1000.00");
        assertThat(t.getCommissionPaid()).isEqualByComparingTo("350.00");
        assertThat(t.getTdsWithheld()).isEqualByComparingTo("7.00");
        assertThat(t.getEarnedEstimate()).isEqualByComparingTo("550.00");
        assertThat(t.getEarnedStatement()).isEqualByComparingTo("180.00");
        assertThat(t.getReversals()).isEqualByComparingTo("400.00");
        assertThat(t.getVoyraNet()).isEqualByComparingTo("-820.00");
    }

    @Test
    void rowsSplitPerAgencyAndPerProduct() {
        given(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).willReturn(sample());
        given(tenantRepository.findAllById(any())).willReturn(List.of(tenant("T1", "Alpha Travels"), tenant("T2", "Beta Tours")));

        PnlResponse r = service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null);

        assertThat(r.getByAgency()).extracting(PnlRow::getLabel).containsExactly("Alpha Travels", "Beta Tours");
        PnlRow alpha = r.getByAgency().get(0);
        assertThat(alpha.getKey()).isEqualTo("T1");
        assertThat(alpha.getBookings()).isEqualTo(2);
        assertThat(alpha.getGmv()).isEqualByComparingTo("14400.00");
        assertThat(alpha.getVoyraNet()).isEqualByComparingTo("-420.00");
        assertThat(r.getByProduct()).extracting(PnlRow::getKey).containsExactly("FLIGHT", "HOTEL");
        assertThat(r.getByProduct().get(1).getReversals()).isEqualByComparingTo("400.00");
        assertThat(r.getByProduct().get(1).getCancellations()).isEqualTo(1);
    }

    @Test
    void filtersNarrowByProductAgencyAndEnvironment() {
        List<PlatformBooking> rows = new java.util.ArrayList<>(sample());
        rows.add(booking("B4", "T1", PricingProduct.FLIGHT, "1000.00", "1000.00", "10.00",
                VoyraEarnedSource.ESTIMATE, "0.00", "0.00", "10.00").toBuilder().environment(SupplierEnvironment.PROD).build());
        given(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).willReturn(rows);
        given(tenantRepository.findAllById(any())).willReturn(List.of(tenant("T1", "Alpha Travels")));

        assertThat(service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PricingProduct.FLIGHT, "T1", null)
                .getTotals().getBookings()).isEqualTo(3);
        assertThat(service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, SupplierEnvironment.PROD)
                .getTotals().getVoyraNet()).isEqualByComparingTo("10.00");
    }

    @Test
    void bookingsStillOpenOrNeverTicketedAreLeftOut() {
        PlatformBooking failedLate = booking("B9", "T1", PricingProduct.FLIGHT, "1.00", "1.00", "1.00",
                VoyraEarnedSource.ESTIMATE, "0.00", "0.00", "1.00").toBuilder().status(PlatformBookingStatus.FAILED).build();
        given(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).willReturn(List.of(failedLate));
        assertThat(service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null).getTotals().getBookings())
                .isZero();
    }

    @Test
    void daysAreIndianStandardTimeAndTheToDateIsInclusive() {
        given(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).willReturn(List.of());
        service.pnl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null);
        verify(bookingRepository).findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(
                Instant.parse("2026-09-30T18:30:00Z"), Instant.parse("2026-10-31T18:30:00Z"));
    }

    @Test
    void fromAfterToIsRefused() {
        assertThatThrownBy(() -> service.pnl(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
