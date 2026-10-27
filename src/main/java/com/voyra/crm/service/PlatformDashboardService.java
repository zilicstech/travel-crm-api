package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformDashboardResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** The platform home tiles (spec 9). Read-only: plain reads and counts, no row locks. */
@Service
@RequiredArgsConstructor
public class PlatformDashboardService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PlatformPnlService pnlService;
    private final WalletRepository walletRepository;
    private final AgencyApplicationRepository applicationRepository;
    private final TopupRequestRepository topupRepository;
    private final PlatformBookingRepository bookingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final NotificationOutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public PlatformDashboardResponse dashboard() {
        LocalDate to = LocalDate.now(IST);
        LocalDate from = to.minusDays(29);
        // same sums as the P&L screen, live bookings only
        PnlRow totals = pnlService.pnl(from, to, null, null, SupplierEnvironment.PROD).getTotals();
        // shortcut: one wallet per agency summed in memory; move to a SUM query past a few thousand agencies
        BigDecimal walletFloat = walletRepository.findAll().stream()
                .filter(w -> !Boolean.TRUE.equals(w.getIsTest()))
                .map(Wallet::getBalance).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return PlatformDashboardResponse.builder()
                .from(from).to(to)
                .gmv30d(totals.getGmv())
                .voyraNet30d(totals.getVoyraNet())
                .walletFloat(walletFloat)
                .pendingKyc(applicationRepository.countByStatusIn(Set.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW)))
                .pendingTopups(topupRepository.countByStatusAndMode(TopupStatus.PENDING, TopupMode.BANK_TRANSFER))
                .unknownBookings(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.UNKNOWN)))
                .cancellingBookings(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.CANCELLING)))
                .pastDueSubscriptions(subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE))
                .failedEmails(outboxRepository.countByStatus(NotificationOutboxStatus.FAILED))
                .build();
    }
}
