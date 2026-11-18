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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform home tiles: GMV and net from the P&L (live bookings, last 30 days), float without test wallets, queue counts. */
@ExtendWith(MockitoExtension.class)
class PlatformDashboardServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private AgencyApplicationRepository applicationRepository;
    @Mock private TopupRequestRepository topupRepository;
    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private com.voyra.crm.repository.PlatformCancelRequestRepository cancelRequestRepository;
    @Mock private com.voyra.crm.repository.TenantRepository tenantRepository;
    @InjectMocks private PlatformDashboardService service;

    private static Wallet wallet(String balance, boolean test) {
        return Wallet.builder().id(balance).tenantId("T" + balance).balance(balance == null ? null : new BigDecimal(balance))
                .held(BigDecimal.ZERO).isTest(test).build();
    }

    @Test
    void tilesComeFromTheBookingsAndTheQueues() {
        when(bookingRepository.sumDisplayedTicketed(any(), any(), eq(SupplierEnvironment.PROD), any())).thenReturn(new BigDecimal("125000.00"));
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
        assertThat(d.getWalletFloat()).isEqualByComparingTo("3500.50"); // the test wallet is left out
        assertThat(d.getPendingKyc()).isEqualTo(3);
        assertThat(d.getPendingTopups()).isEqualTo(2);
        assertThat(d.getUnknownBookings()).isEqualTo(1);
        assertThat(d.getCancellingBookings()).isEqualTo(4);
        assertThat(d.getPastDueSubscriptions()).isEqualTo(5);
        assertThat(d.getFailedEmails()).isEqualTo(6);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(d.getTo()).isEqualTo(today);
        assertThat(d.getFrom()).isEqualTo(today.minusDays(29)); // 30 IST days including today
    }

    @Test
    void gmvIsTheDisplayedSumOfLiveBookingsTicketedInTheLast30IndianDays() {
        service.dashboard();
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        verify(bookingRepository).sumDisplayedTicketed(today.minusDays(29).atStartOfDay(ist).toInstant(),
                today.plusDays(1).atStartOfDay(ist).toInstant(), SupplierEnvironment.PROD,
                Set.of(PlatformBookingStatus.TICKETED, PlatformBookingStatus.CANCELLING, PlatformBookingStatus.CANCELLED));
    }

    @Test
    void noWalletsMeansZeroFloat() {
        when(bookingRepository.sumDisplayedTicketed(any(), any(), eq(SupplierEnvironment.PROD), any())).thenReturn(BigDecimal.ZERO);
        when(walletRepository.findAll()).thenReturn(List.of(wallet("50.00", true)));

        assertThat(service.dashboard().getWalletFloat()).isEqualByComparingTo("0");
    }

    @Test
    void pendingTasksPutCancellationRequestsFirstSoonestTravelFirstThenTheOtherQueuesOldestFirst() {
        java.time.Instant t0 = java.time.Instant.parse("2026-11-01T00:00:00Z");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        java.util.function.BiFunction<String, String, com.voyra.crm.entity.PlatformCancelRequest> request = (id, booking) ->
                com.voyra.crm.entity.PlatformCancelRequest.builder().id(id).platformBookingId(booking).tenantId(id.equals("R2") ? "T9" : "T1")
                        .reason(com.voyra.crm.enums.CancelReason.NO_SHOW).createdAt(t0.plusSeconds(id.hashCode() % 100)).build();
        when(cancelRequestRepository.findByStatusIn(com.voyra.crm.enums.CancelRequestStatus.ACTIVE)).thenReturn(List.of(
                request.apply("R1", "B1"), request.apply("R2", "GONE"), request.apply("R3", "B3"), request.apply("R4", "B4")));
        when(bookingRepository.findAllById(any())).thenReturn(List.of(
                com.voyra.crm.entity.PlatformBooking.builder().id("B1").product(com.voyra.crm.enums.PricingProduct.FLIGHT).pnr("P1")
                        .pricingSnapshot(new java.util.HashMap<>(java.util.Map.of("travelDate", today.plusDays(1).toString()))).build(),
                com.voyra.crm.entity.PlatformBooking.builder().id("B3").product(com.voyra.crm.enums.PricingProduct.HOTEL).pnr("HC3")
                        .itinerary(java.util.Map.of("stay", java.util.Map.of("checkIn", today.plusDays(20).toString()))).build(),
                com.voyra.crm.entity.PlatformBooking.builder().id("B4").product(com.voyra.crm.enums.PricingProduct.FLIGHT).build()));
        when(bookingRepository.findByStatusIn(Set.of(PlatformBookingStatus.CANCELLING, PlatformBookingStatus.UNKNOWN))).thenReturn(List.of(
                com.voyra.crm.entity.PlatformBooking.builder().id("S1").tenantId("T1").status(PlatformBookingStatus.CANCELLING).pnr("PX").createdAt(t0.plusSeconds(30)).build(),
                com.voyra.crm.entity.PlatformBooking.builder().id("S2").tenantId("T1").status(PlatformBookingStatus.UNKNOWN).createdAt(t0.plusSeconds(10)).build()));
        when(tenantRepository.findAllById(any())).thenReturn(List.of(com.voyra.crm.entity.Tenant.builder().id("T1").agencyName("Sky Tours").build()));
        when(applicationRepository.findByStatusIn(Set.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW))).thenReturn(List.of(
                com.voyra.crm.entity.AgencyApplication.builder().id("A1").agencyName("New Air").status(ApplicationStatus.UNDER_REVIEW).createdAt(t0.plusSeconds(5)).build(),
                com.voyra.crm.entity.AgencyApplication.builder().id("A2").agencyName("Fly Co").status(ApplicationStatus.SUBMITTED)
                        .createdAt(t0).submittedAt(t0.plusSeconds(20)).build()));
        when(topupRepository.findByStatusOrderByCreatedAtDesc(TopupStatus.PENDING)).thenReturn(List.of(
                com.voyra.crm.entity.TopupRequest.builder().id("TU1").mode(TopupMode.BANK_TRANSFER).amount(new BigDecimal("5000")).build(),
                com.voyra.crm.entity.TopupRequest.builder().id("TU2").mode(TopupMode.GATEWAY).amount(BigDecimal.ONE).createdAt(t0).build()));

        PlatformDashboardResponse d = service.dashboard();

        assertThat(d.getPendingTasks()).extracting(PlatformDashboardResponse.PendingTask::href).containsExactly(
                "/platform/cancellations/R1", "/platform/cancellations/R3", "/platform/cancellations/R2", "/platform/cancellations/R4",
                "/platform/applications/A1", "/platform/bookings/S2", "/platform/applications/A2", "/platform/bookings/S1", "/platform/topups");
        PlatformDashboardResponse.PendingTask soon = d.getPendingTasks().get(0);
        assertThat(soon.title()).isEqualTo("Cancellation request - Sky Tours");
        assertThat(soon.subtitle()).isEqualTo("Passenger no-show · PNR P1");
        assertThat(soon.urgent()).isTrue();
        assertThat(d.getPendingTasks().get(1).subtitle()).isEqualTo("Passenger no-show · Confirmation HC3");
        assertThat(d.getPendingTasks().get(1).urgent()).isFalse();
        assertThat(d.getPendingTasks().get(2).title()).isEqualTo("Cancellation request - T9"); // an unknown agency shows its id
        assertThat(d.getPendingTasks().get(2).dueAt()).isNull();
        assertThat(d.getPendingTasks().get(3).subtitle()).isEqualTo("Passenger no-show");
        assertThat(d.getPendingTasks()).filteredOn(t -> t.kind().equals("UNKNOWN_BOOKING")).singleElement()
                .satisfies(t -> assertThat(t.subtitle()).isEqualTo("S2"));
        assertThat(d.getPendingTasks()).filteredOn(t -> t.kind().equals("CANCELLING")).singleElement()
                .satisfies(t -> assertThat(t.title()).isEqualTo("Cancellation awaiting the supplier - Sky Tours"));
        assertThat(d.getPendingTasks()).filteredOn(t -> t.kind().equals("KYC")).extracting(PlatformDashboardResponse.PendingTask::subtitle)
                .containsExactly("under review", "submitted");
        assertThat(d.getPendingTaskTotal()).isEqualTo(9);
    }

    @Test
    void theWorkListShowsAtMostTwentyTasks() {
        java.util.List<com.voyra.crm.entity.TopupRequest> many = java.util.stream.IntStream.range(0, 23).mapToObj(i ->
                com.voyra.crm.entity.TopupRequest.builder().id("TU" + i).mode(TopupMode.BANK_TRANSFER).amount(BigDecimal.TEN)
                        .createdAt(java.time.Instant.EPOCH.plusSeconds(i)).build()).toList();
        when(topupRepository.findByStatusOrderByCreatedAtDesc(TopupStatus.PENDING)).thenReturn(many);
        PlatformDashboardResponse d = service.dashboard();
        assertThat(d.getPendingTasks()).hasSize(20);
        assertThat(d.getPendingTaskTotal()).isEqualTo(23);
    }
}
