package com.voyra.crm.service;

import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private SubscriptionBillingService billingService;
    @Mock private EntitlementService entitlementService;
    @Mock private NotificationService notificationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TenantRepository tenantRepository;

    private SubscriptionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionLifecycleService(subscriptionRepository, invoiceRepository, billingService,
                entitlementService, notificationService, transactionManager, tenantRepository);
    }

    private Subscription stub(Subscription s) {
        when(subscriptionRepository.findByTenantIdForUpdate("T1")).thenReturn(Optional.of(s));
        return s;
    }

    private static Subscription.SubscriptionBuilder paid() {
        return Subscription.builder().id("S1").tenantId("T1").plan(SubscriptionPlan.SUITE).status(SubscriptionStatus.ACTIVE)
                .billingCycle(BillingCycle.MONTHLY).periodEnd(NOW.plus(Duration.ofDays(30))).updatedAt(NOW);
    }

    @Test
    void aTenantWithoutASubscriptionIsSkipped() {
        assertThat(service.processTenant("T1", NOW)).isNull();
        verifyNoInteractions(billingService, tenantRepository);
    }

    @Test
    void cancelledAndBasicPlansAreNeverBilled() {
        stub(paid().status(SubscriptionStatus.CANCELLED).build());
        assertThat(service.processTenant("T1", NOW)).isNull();
        stub(paid().plan(SubscriptionPlan.STARTER).build());
        assertThat(service.processTenant("T1", NOW)).isNull();

        verify(billingService, org.mockito.Mockito.times(2)).lapseUpgrades("T1", NOW);
        verifyNoInteractions(tenantRepository, invoiceRepository);
    }

    @Test
    void aDeactivatedAgencyIsBilledAndToldNothing() {
        Subscription s = stub(paid().periodEnd(NOW.minus(Duration.ofDays(1))).build());
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").status(TenantStatus.DEACTIVATED).build()));

        assertThat(service.processTenant("T1", NOW)).isNull();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(billingService, never()).raiseRenewal(any(), any());
    }

    @Test
    void aTrialWithoutItsOwnEndUsesThePeriodEndAndOneWithNoEndAtAllLapses() {
        Subscription byPeriod = stub(paid().status(SubscriptionStatus.TRIAL).trialEndsAt(null).periodEnd(NOW.plus(Duration.ofDays(5))).build());
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").status(TenantStatus.ACTIVE).build()));

        service.processTenant("T1", NOW);

        assertThat(byPeriod.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(byPeriod.getExpiryNoticeDays()).isEqualTo(7);
        verify(notificationService).subscriptionExpiring("T1", "TRIAL", NOW.plus(Duration.ofDays(5)), 5);

        Subscription endless = stub(paid().status(SubscriptionStatus.TRIAL).trialEndsAt(null).periodEnd(null).build());
        service.processTenant("T1", NOW);
        assertThat(endless.getStatus()).isEqualTo(SubscriptionStatus.READ_ONLY);
        verify(entitlementService).refresh("T1");
    }

    @Test
    void aPaidPlanWithNoPeriodEndIsActiveAndGetsNoNotice() {
        Subscription s = stub(paid().status(SubscriptionStatus.PAST_DUE).periodEnd(null).build());
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(billingService, never()).raiseRenewal(any(), any());
        verify(notificationService, never()).subscriptionExpiring(any(), any(), any(), anyInt());
        verify(entitlementService).refresh("T1");
    }

    @Test
    void aNoticeIsResentOnlyForASmallerThresholdOrANewEndDate() {
        Instant end = NOW.plus(Duration.ofHours(20));
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());
        when(invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc("T1", "ISSUED")).thenReturn(Optional.empty());

        Subscription after7 = stub(paid().periodEnd(end).expiryNoticeFor(end).expiryNoticeDays(7).build());
        service.processTenant("T1", NOW);
        assertThat(after7.getExpiryNoticeDays()).isEqualTo(1);

        Subscription unknownDays = stub(paid().periodEnd(end).expiryNoticeFor(end).expiryNoticeDays(null).build());
        service.processTenant("T1", NOW);
        assertThat(unknownDays.getExpiryNoticeDays()).isEqualTo(1);

        Subscription alreadySent = stub(paid().periodEnd(end).expiryNoticeFor(end).expiryNoticeDays(1).build());
        service.processTenant("T1", NOW);

        verify(notificationService, org.mockito.Mockito.times(2)).subscriptionExpiring(eq("T1"), eq("PERIOD"), eq(end), eq(1));
        verify(subscriptionRepository, never()).save(alreadySent);
    }

    @Test
    void anInvoiceDueTodayMakesThePlanPastDue() {
        Subscription s = stub(paid().build());
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());
        when(invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc("T1", "ISSUED"))
                .thenReturn(Optional.of(SubscriptionInvoice.builder().id("I1").dueAt(NOW).build()));

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void aWaiverWithAPeriodEndIsTreatedAsAWaiverNotABill() {
        Subscription s = stub(paid().waivedReason(WaivedReason.COMPLIMENTARY).periodEnd(NOW.plus(Duration.ofDays(60))).build());
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());

        service.processTenant("T1", NOW);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verifyNoInteractions(invoiceRepository, notificationService);
    }
}
