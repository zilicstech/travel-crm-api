package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformDashboardResponse;
import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.dto.PnlRow;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.NotificationOutboxStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.NotificationOutboxRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform home tiles: GMV and net from the P&L (live bookings, last 30 days), float without test wallets, queue counts. */
@ExtendWith(MockitoExtension.class)
class PlatformDashboardServiceTest {

    @Mock private PlatformPnlService pnlService;
    @Mock private WalletRepository walletRepository;
    @Mock private AgencyApplicationRepository applicationRepository;
    @Mock private TopupRequestRepository topupRepository;
    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private NotificationOutboxRepository outboxRepository;
    @InjectMocks private PlatformDashboardService service;

    private static Wallet wallet(String balance, boolean test) {
        return Wallet.builder().id(balance).tenantId("T" + balance).balance(balance == null ? null : new BigDecimal(balance))
                .held(BigDecimal.ZERO).isTest(test).build();
    }

    @Test
    void tilesComeFromThePnlAndTheQueues() {
        when(pnlService.pnl(any(), any(), isNull(), isNull(), eq(SupplierEnvironment.PROD))).thenReturn(PnlResponse.builder()
                .totals(PnlRow.builder().gmv(new BigDecimal("125000.00")).voyraNet(new BigDecimal("-310.50")).build()).build());
        when(walletRepository.findAll()).thenReturn(List.of(wallet("1000.50", false), wallet("2500.00", false),
                wallet("999999.00", true), wallet(null, false)));
        when(applicationRepository.countByStatusIn(Set.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW))).thenReturn(3L);
        when(topupRepository.countByStatusAndMode(TopupStatus.PENDING, TopupMode.BANK_TRANSFER)).thenReturn(2L);
        when(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.UNKNOWN))).thenReturn(1L);
        when(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.CANCELLING))).thenReturn(4L);
        when(subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE)).thenReturn(5L);
        when(outboxRepository.countByStatus(NotificationOutboxStatus.FAILED)).thenReturn(6L);

        PlatformDashboardResponse d = service.dashboard();

        assertThat(d.getGmv30d()).isEqualByComparingTo("125000.00");
        assertThat(d.getVoyraNet30d()).isEqualByComparingTo("-310.50");
        assertThat(d.getWalletFloat()).isEqualByComparingTo("3500.50"); // the test wallet is left out
        assertThat(d.getPendingKyc()).isEqualTo(3);
        assertThat(d.getPendingTopups()).isEqualTo(2);
        assertThat(d.getUnknownBookings()).isEqualTo(1);
        assertThat(d.getCancellingBookings()).isEqualTo(4);
        assertThat(d.getPastDueSubscriptions()).isEqualTo(5);
        assertThat(d.getFailedEmails()).isEqualTo(6);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(pnlService).pnl(from.capture(), to.capture(), isNull(), isNull(), eq(SupplierEnvironment.PROD));
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(29)); // 30 IST days including today
        assertThat(d.getFrom()).isEqualTo(from.getValue());
        assertThat(d.getTo()).isEqualTo(to.getValue());
    }

    @Test
    void noWalletsMeansZeroFloat() {
        when(pnlService.pnl(any(), any(), isNull(), isNull(), eq(SupplierEnvironment.PROD))).thenReturn(PnlResponse.builder()
                .totals(PnlRow.builder().gmv(BigDecimal.ZERO).voyraNet(BigDecimal.ZERO).build()).build());
        when(walletRepository.findAll()).thenReturn(List.of(wallet("50.00", true)));

        assertThat(service.dashboard().getWalletFloat()).isEqualByComparingTo("0");
    }
}
