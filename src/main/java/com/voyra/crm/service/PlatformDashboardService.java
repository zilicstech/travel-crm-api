package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformDashboardResponse;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import java.util.Set;

/** The platform home tiles (spec 9). Read-only: plain reads and counts, no row locks. */
@Service
@RequiredArgsConstructor
public class PlatformDashboardService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<PlatformBookingStatus> COUNTED =
            Set.of(PlatformBookingStatus.TICKETED, PlatformBookingStatus.CANCELLING, PlatformBookingStatus.CANCELLED);

    private final WalletRepository walletRepository;
    private final AgencyApplicationRepository applicationRepository;
    private final TopupRequestRepository topupRepository;
    private final PlatformBookingRepository bookingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final com.voyra.crm.repository.PlatformCancelRequestRepository cancelRequestRepository;
    private final com.voyra.crm.repository.TenantRepository tenantRepository;
    static final int TASKS_SHOWN = 20;

    @Transactional(readOnly = true)
    public PlatformDashboardResponse dashboard() {
        LocalDate to = LocalDate.now(IST);
        LocalDate from = to.minusDays(29);
        BigDecimal gmv = bookingRepository.sumDisplayedTicketed(from.atStartOfDay(IST).toInstant(),
                to.plusDays(1).atStartOfDay(IST).toInstant(), SupplierEnvironment.PROD, COUNTED);
        // shortcut: one wallet per agency summed in memory; move to a SUM query past a few thousand agencies
        BigDecimal walletFloat = walletRepository.findAll().stream()
                .filter(w -> !Boolean.TRUE.equals(w.getIsTest()))
                .map(Wallet::getBalance).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PlatformDashboardResponse.PendingTask> tasks = tasks(to);
        return PlatformDashboardResponse.builder()
                .from(from).to(to)
                .gmv30d(gmv)
                .walletFloat(walletFloat)
                .pendingKyc(applicationRepository.countByStatusIn(Set.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW)))
                .pendingTopups(topupRepository.countByStatusAndMode(TopupStatus.PENDING, TopupMode.BANK_TRANSFER))
                .unknownBookings(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.UNKNOWN)))
                .cancellingBookings(bookingRepository.countByStatusIn(Set.of(PlatformBookingStatus.CANCELLING)))
                .pastDueSubscriptions(subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE))
                .failedEmails(outboxRepository.countByStatus(NotificationOutboxStatus.FAILED))
                .cancelRequests(cancelRequestRepository.countByStatusIn(CancelRequestStatus.ACTIVE))
                .pendingTasks(tasks.stream().limit(TASKS_SHOWN).toList())
                .pendingTaskTotal(tasks.size())
                .build();
    }

    /** Cancellation requests first, soonest travel first (undated last); then the other queues oldest first. */
    private List<PlatformDashboardResponse.PendingTask> tasks(LocalDate today) {
        List<PlatformCancelRequest> requests = cancelRequestRepository.findByStatusIn(CancelRequestStatus.ACTIVE);
        Map<String, PlatformBooking> bookings = bookingRepository.findAllById(requests.stream().map(PlatformCancelRequest::getPlatformBookingId).toList())
                .stream().collect(Collectors.toMap(PlatformBooking::getId, b -> b));
        List<PlatformBooking> stuck = bookingRepository.findByStatusIn(Set.of(PlatformBookingStatus.CANCELLING, PlatformBookingStatus.UNKNOWN));
        Map<String, String> agencies = new HashMap<>();
        tenantRepository.findAllById(java.util.stream.Stream.concat(requests.stream().map(PlatformCancelRequest::getTenantId),
                stuck.stream().map(PlatformBooking::getTenantId)).distinct().toList()).forEach(t -> agencies.put(t.getId(), t.getAgencyName()));

        List<PlatformDashboardResponse.PendingTask> cancels = new ArrayList<>();
        for (PlatformCancelRequest r : requests) {
            PlatformBooking b = bookings.get(r.getPlatformBookingId());
            String due = b == null ? null : PlatformCancelRequestService.travelDate(b);
            String pnr = b == null || b.getPnr() == null ? "" : " · " + (b.getProduct() == PricingProduct.HOTEL ? "Confirmation " : "PNR ") + b.getPnr();
            cancels.add(new PlatformDashboardResponse.PendingTask("CANCEL_REQUEST", "Cancellation request - " + agencies.getOrDefault(r.getTenantId(), r.getTenantId()),
                    r.getReason().label() + pnr, r.getCreatedAt(), due, due != null && !LocalDate.parse(due).isAfter(today.plusDays(1)),
                    "/platform/cancellations/" + r.getId()));
        }
        cancels.sort(Comparator.comparing(PlatformDashboardResponse.PendingTask::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlatformDashboardResponse.PendingTask::createdAt));

        List<PlatformDashboardResponse.PendingTask> rest = new ArrayList<>();
        for (PlatformBooking b : stuck) {
            boolean cancelling = b.getStatus() == PlatformBookingStatus.CANCELLING;
            rest.add(new PlatformDashboardResponse.PendingTask(cancelling ? "CANCELLING" : "UNKNOWN_BOOKING",
                    (cancelling ? "Cancellation awaiting the supplier - " : "Booking outcome unknown - ") + agencies.getOrDefault(b.getTenantId(), b.getTenantId()),
                    b.getPnr() == null ? b.getId() : b.getPnr(), b.getCreatedAt(), null, false,
                    "/platform/bookings/" + b.getId()));
        }
        applicationRepository.findByStatusIn(Set.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW)).forEach(a ->
                rest.add(new PlatformDashboardResponse.PendingTask("KYC", "Agency application - " + a.getAgencyName(), a.getStatus().name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT),
                        a.getSubmittedAt() == null ? a.getCreatedAt() : a.getSubmittedAt(), null, false, "/platform/applications/" + a.getId())));
        topupRepository.findByStatusOrderByCreatedAtDesc(TopupStatus.PENDING).stream().filter(t -> t.getMode() == TopupMode.BANK_TRANSFER).forEach(t ->
                rest.add(new PlatformDashboardResponse.PendingTask("TOPUP", "Bank top-up to approve", "₹" + t.getAmount(), t.getCreatedAt(), null, false, "/platform/topups")));
        rest.sort(Comparator.comparing(PlatformDashboardResponse.PendingTask::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        cancels.addAll(rest);
        return cancels;
    }
}
