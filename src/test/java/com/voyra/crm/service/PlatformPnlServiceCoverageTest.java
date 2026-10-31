package com.voyra.crm.service;

import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformPnlServiceCoverageTest {

    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private TenantRepository tenantRepository;
    @InjectMocks private PlatformPnlService service;

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    private static PlatformBooking booking(String id, String tenantId) {
        return PlatformBooking.builder().id(id).tenantId(tenantId).product(PricingProduct.FLIGHT).status(PlatformBookingStatus.TICKETED)
                .environment(SupplierEnvironment.PROD).gross(new BigDecimal("1000")).displayed(new BigDecimal("950"))
                .voyraEarned(new BigDecimal("10")).agencyCommission(BigDecimal.ZERO).tds(BigDecimal.ZERO).voyraNet(new BigDecimal("10"))
                .ticketedAt(Instant.parse("2026-10-05T06:00:00Z")).build();
    }

    @Test
    void aRangeOverAYearIsRefused() {
        assertThatThrownBy(() -> service.pnl(FROM, FROM.plusYears(1).plusDays(1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Pick a range of at most one year");
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void oneAgencysPnlLeavesTheOthersOutAndAnUnnamedAgencySortsFirst() {
        when(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).thenReturn(List.of(
                booking("B1", "T1"), booking("B2", "T2")));
        when(tenantRepository.findAllById(List.of("T1"))).thenReturn(List.of());
        PnlResponse one = service.pnl(FROM, FROM.plusDays(30), null, "T1", null);
        assertThat(one.getByAgency()).extracting(r -> r.getKey()).containsExactly("T1");
        assertThat(one.getByAgency().get(0).getLabel()).isEqualTo("T1");

        when(bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(any(), any())).thenReturn(List.of(
                booking("B2", "T2"), booking("B1", "T1")));
        Tenant unnamed = new Tenant();
        unnamed.setId("T1");
        Tenant zen = new Tenant();
        zen.setId("T2");
        zen.setAgencyName("Zen Travels");
        when(tenantRepository.findAllById(List.of("T2", "T1"))).thenReturn(List.of(unnamed, zen));
        PnlResponse all = service.pnl(FROM, FROM.plusDays(30), null, null, null);
        assertThat(all.getByAgency()).extracting(r -> r.getLabel()).containsExactly(null, "Zen Travels");
    }
}
