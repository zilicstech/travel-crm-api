package com.voyra.crm.service;

import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private SubscriptionBillingService billingService;
    @Mock private EntitlementService entitlementService;
    @Mock private NotificationService notificationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.voyra.crm.repository.TenantRepository tenantRepository;

    private SubscriptionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionLifecycleService(subscriptionRepository, invoiceRepository, billingService,
                entitlementService, notificationService, transactionManager, tenantRepository);
        lenient().when(invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc("T1", "ISSUED")).thenReturn(Optional.empty());
    }

    private Subscription stub(Subscription s) {
        lenient().when(subscriptionRepository.findByTenantIdForUpdate("T1")).thenReturn(Optional.of(s));
        return s;
    }

    private static Subscription paid(Instant periodEnd) {
        return Subscription.builder().id("S1").tenantId("T1").plan(SubscriptionPlan.SUITE)
                .skus(EnumSet.allOf(Sku.class).stream().map(Enum::name).toList()).seats(0)
                .status(SubscriptionStatus.ACTIVE).billingCycle(BillingCycle.MONTHLY)
                .periodStart(periodEnd.minus(Duration.ofDays(30))).periodEnd(periodEnd).updatedAt(NOW).build();
    }

    private static Subscription trial(Instant end) {
        return paid(end).toBuilder().status(SubscriptionStatus.TRIAL).trialEndsAt(end).build();
    }

    private void overdue(Instant dueAt) {
        lenient().when(invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc("T1", "ISSUED"))
                .thenReturn(Optional.of(SubscriptionInvoice.builder().id("I1").tenantId("T1").status("ISSUED").dueAt(dueAt).build()));
    }

    @Test
    void trialEndGoesStraightToReadOnly() {
        Subscription s = stub(trial(NOW.minus(Duration.ofHours(1))));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
        verify(subscriptionRepository).save(s);
        verify(entitlementService).refresh("T1");
        verify(billingService, never()).raiseRenewal(any(), any());
    }

    @Test
    void complimentaryEndGoesStraightToReadOnlyWithoutAnInvoice() {
        Subscription s = stub(paid(NOW.minus(Duration.ofMinutes(5))).toBuilder().waivedReason(WaivedReason.COMPLIMENTARY).build());

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
        verify(billingService, never()).raiseRenewal(any(), any());
        verify(entitlementService).refresh("T1");
    }

    @Test
    void foundingAgencyNeverExpiresIsNeverInvoicedAndNeverReadOnly() {
        Subscription s = stub(paid(NOW).toBuilder().waivedReason(WaivedReason.FOUNDING_AGENCY).periodEnd(null).build());
        overdue(NOW.minus(Duration.ofDays(30)));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(billingService, never()).raiseRenewal(any(), any());  // an abandoned upgrade may lapse, nothing is billed
        verifyNoInteractions(entitlementService, notificationService);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void paidPlanAtPeriodEndRaisesTheRenewalInvoice() {
        Subscription s = stub(paid(NOW.minus(Duration.ofHours(2))));
        given(billingService.raiseRenewal(s, NOW)).willReturn(Optional.of(SubscriptionInvoice.builder().id("I9").build()));

        assertThat(service.processTenant("T1", NOW)).isEqualTo("I9");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(entitlementService, never()).refresh(anyString());
    }

    @Test
    void runningTrialAndRunningWaiverAreNeverInvoiced() {
        stub(trial(NOW.plus(Duration.ofDays(20))));
        service.processTenant("T1", NOW);
        stub(paid(NOW.plus(Duration.ofDays(20))).toBuilder().waivedReason(WaivedReason.COMPLIMENTARY).build());
        service.processTenant("T1", NOW);

        verify(billingService, never()).raiseRenewal(any(), any());
    }

    @Test
    void issuedInvoicePastDueMovesToPastDueAndStaysWritable() {
        Subscription s = stub(paid(NOW.plus(Duration.ofDays(20))));
        overdue(NOW.minus(Duration.ofDays(1)));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        verify(entitlementService).refresh("T1");
    }

    @Test
    void notYetDueStaysActive() {
        Subscription s = stub(paid(NOW.plus(Duration.ofDays(20))));
        overdue(NOW.plus(Duration.ofHours(1)));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(entitlementService, never()).refresh(anyString());
    }

    @Test
    void sevenDaysPastDueMovesToReadOnly() {
        Subscription s = stub(paid(NOW.plus(Duration.ofDays(20))).toBuilder().status(SubscriptionStatus.PAST_DUE).build());
        overdue(NOW.minus(Duration.ofDays(7)));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
        verify(entitlementService).refresh("T1");
    }

    @Test
    void pastDueWithNothingOverdueAnyMoreIsActiveAgain() {
        Subscription s = stub(paid(NOW.plus(Duration.ofDays(20))).toBuilder().status(SubscriptionStatus.PAST_DUE).build());

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void readOnlyStaysUntilPaidWaivedOrANewTrial() {
        Subscription s = stub(paid(NOW.minus(Duration.ofDays(40))).toBuilder().status(SubscriptionStatus.READ_ONLY).build());

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
        verify(billingService, never()).raiseRenewal(any(), any());  // an abandoned upgrade may lapse, nothing is billed
        verifyNoInteractions(notificationService);
    }

    @Test
    void expiryNoticesFireOnceEachAt14And7And1Days() {
        Instant end = NOW.plus(Duration.ofDays(14));
        Subscription s = stub(trial(end));

        for (int day = 0; day < 14; day++) {
            service.processTenant("T1", NOW.plus(Duration.ofDays(day)));
        }
        service.processTenant("T1", NOW.plus(Duration.ofDays(13)));  // a second run on the same day

        verify(notificationService).subscriptionExpiring("T1", "TRIAL", end, 14);
        verify(notificationService).subscriptionExpiring("T1", "TRIAL", end, 7);
        verify(notificationService).subscriptionExpiring("T1", "TRIAL", end, 1);
        verify(notificationService, times(3)).subscriptionExpiring(anyString(), anyString(), any(), anyInt());
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    void aMissedDayStillSendsTheNextNoticeOnceAndANewEndStartsAgain() {
        Instant end = NOW.plus(Duration.ofDays(5));
        Subscription s = stub(paid(end).toBuilder().waivedReason(WaivedReason.COMPLIMENTARY).build());

        service.processTenant("T1", NOW);
        service.processTenant("T1", NOW.plus(Duration.ofDays(1)));
        verify(notificationService, times(1)).subscriptionExpiring("T1", "WAIVER", end, 5);

        Instant later = NOW.plus(Duration.ofDays(3));
        s.setPeriodEnd(later);
        service.processTenant("T1", NOW);
        verify(notificationService).subscriptionExpiring("T1", "WAIVER", later, 3);
    }

    @Test
    void oneTenantFailingDoesNotStopTheOthers() {
        given(subscriptionRepository.findAllTenantIds()).willReturn(List.of("T0", "T1"));
        given(subscriptionRepository.findByTenantIdForUpdate("T0")).willThrow(new IllegalStateException("boom"));
        Subscription s = stub(trial(Instant.now().minus(Duration.ofDays(1))));

        service.runDaily();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
    }

    @Test
    void aRaisedInvoiceGetsItsPdfAfterTheTenantCommits() {
        given(subscriptionRepository.findAllTenantIds()).willReturn(List.of("T1"));
        Subscription s = stub(paid(Instant.now().minus(Duration.ofHours(1))));
        given(billingService.raiseRenewal(eq(s), any())).willReturn(Optional.of(SubscriptionInvoice.builder().id("I9").build()));

        service.runDaily();

        verify(billingService).ensurePdfQuietly("I9");
    }

    @Test
    void deactivatedTenantGetsNoInvoiceNoticeAndNoChange() {
        Subscription s = stub(paid(NOW.minus(Duration.ofHours(2))));
        com.voyra.crm.entity.Tenant t = new com.voyra.crm.entity.Tenant();
        t.setStatus(com.voyra.crm.enums.TenantStatus.DEACTIVATED);
        given(tenantRepository.findById("T1")).willReturn(Optional.of(t));

        assertThat(service.processTenant("T1", NOW)).isNull();

        verify(billingService, never()).raiseRenewal(any(), any());  // an abandoned upgrade may lapse, nothing is billed
        verifyNoInteractions(notificationService, entitlementService);
        verify(subscriptionRepository, never()).save(s);
    }
}
