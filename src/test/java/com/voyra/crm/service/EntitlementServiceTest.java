package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.exception.TenantWriteRefusedException;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Resolution matrix: plan x status x waiver, plus the TenantCache round trip. */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    private static final Instant NOW = Instant.now();
    private static final Instant FUTURE = NOW.plus(Duration.ofDays(10));
    private static final Instant PAST = NOW.minus(Duration.ofDays(1));
    private static final Set<Sku> ALL = EnumSet.allOf(Sku.class);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private EntitlementService entitlementService;

    @AfterEach
    void forgetTenant() {
        TenantCache.remove("T1");
    }

    @Test
    void suiteActiveUnlocksEverySkuWithFiveSeatsPlusExtras() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE).seats(3).build(), NOW);
        assertThat(e.skus()).isEqualTo(ALL);
        assertThat(e.seats()).isEqualTo(8);
        assertThat(e.readOnly()).isFalse();
        assertThat(e.plan()).isEqualTo(SubscriptionPlan.SUITE);
    }

    @Test
    void liteUnlocksEverythingButAccountsWithThreeSeatsPlusExtras() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.LITE, SubscriptionStatus.ACTIVE).seats(2).build(), NOW);
        assertThat(e.skus()).containsExactly(Sku.CRM_CORE, Sku.STAFF_SEATS, Sku.REPORTS, Sku.VISA);
        assertThat(e.seats()).isEqualTo(5);
        assertThat(e.readOnly()).isFalse();
        assertThat(e.plan()).isEqualTo(SubscriptionPlan.LITE);
    }

    @Test
    void runningTrialIsUsableAndSaysWhenItEnds() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL)
                .trialEndsAt(FUTURE).periodEnd(FUTURE).build(), NOW);
        assertThat(e.skus()).isEqualTo(ALL);
        assertThat(e.seats()).isEqualTo(5);
        assertThat(e.readOnly()).isFalse();
        assertThat(e.accessEndsAt()).isEqualTo(FUTURE);
    }

    @Test
    void trialPastItsEndIsReadOnlyEvenBeforeTheJobFlipsIt() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL)
                .trialEndsAt(PAST).periodEnd(PAST).build(), NOW);
        assertThat(e.readOnly()).isTrue();
        assertThat(e.skus()).isEqualTo(ALL);
        assertThat(e.accessEndsAt()).isNull();
    }

    @Test
    void foundingAgencyWaiverNeverExpiresAndIsNeverReadOnly() {
        Entitlements active = EntitlementService.compute(waived(WaivedReason.FOUNDING_AGENCY, null, SubscriptionStatus.ACTIVE), NOW);
        assertThat(active.readOnly()).isFalse();
        assertThat(active.skus()).isEqualTo(ALL);
        assertThat(active.accessEndsAt()).isNull();
        // even a wrong READ_ONLY status cannot lock out an unexpired waiver
        assertThat(EntitlementService.compute(waived(WaivedReason.FOUNDING_AGENCY, null, SubscriptionStatus.READ_ONLY), NOW)
                .readOnly()).isFalse();
    }

    @Test
    void complimentaryWaiverWorksUntilItsEndThenReadOnly() {
        Entitlements running = EntitlementService.compute(waived(WaivedReason.COMPLIMENTARY, FUTURE, SubscriptionStatus.ACTIVE), NOW);
        assertThat(running.readOnly()).isFalse();
        assertThat(running.accessEndsAt()).isEqualTo(FUTURE);

        Entitlements over = EntitlementService.compute(waived(WaivedReason.COMPLIMENTARY, PAST, SubscriptionStatus.ACTIVE), NOW);
        assertThat(over.readOnly()).isTrue();
        assertThat(over.accessEndsAt()).isNull();
    }

    @Test
    void customUnlocksOnlyTheListedSkusAndItsSeats() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.CUSTOM, SubscriptionStatus.ACTIVE)
                .skus(List.of("CRM_CORE", "VISA", "NOT_A_SKU")).seats(2).build(), NOW);
        assertThat(e.skus()).containsExactlyInAnyOrder(Sku.CRM_CORE, Sku.VISA);
        assertThat(e.seats()).isZero(); // seats only count with the STAFF_SEATS SKU
        assertThat(e.readOnly()).isFalse();
    }

    @Test
    void customSeatsCountOnlyWithTheStaffSeatsSku() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.CUSTOM, SubscriptionStatus.ACTIVE)
                .skus(List.of("CRM_CORE", "STAFF_SEATS")).seats(2).build(), NOW);
        assertThat(e.seats()).isEqualTo(2);
    }

    @Test
    void starterHasNoCrmSkusAndNoSeats() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE)
                .skus(List.of("CRM_CORE")).seats(4).build(), NOW);
        assertThat(e.skus()).isEmpty();
        assertThat(e.seats()).isZero();
        assertThat(e.readOnly()).isFalse();
    }

    @Test
    void pastDueStaysUsableDuringGrace() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.PAST_DUE).build(), NOW);
        assertThat(e.readOnly()).isFalse();
        assertThat(e.skus()).isEqualTo(ALL);
    }

    @Test
    void readOnlyKeepsTheSkusForReadsButFlagsWrites() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.CUSTOM, SubscriptionStatus.READ_ONLY)
                .skus(List.of("REPORTS")).build(), NOW);
        assertThat(e.readOnly()).isTrue();
        assertThat(e.skus()).containsExactly(Sku.REPORTS);
    }

    @Test
    void cancelledFallsBackToStarter() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.CANCELLED).seats(3).build(), NOW);
        assertThat(e.plan()).isEqualTo(SubscriptionPlan.STARTER);
        assertThat(e.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(e.skus()).isEmpty();
        assertThat(e.seats()).isZero();
        assertThat(e.readOnly()).isFalse();
    }

    @Test
    void noSubscriptionRowMeansStarter() {
        Entitlements e = EntitlementService.compute(null, NOW);
        assertThat(e.plan()).isEqualTo(SubscriptionPlan.STARTER);
        assertThat(e.skus()).isEmpty();
        assertThat(e.readOnly()).isFalse();
    }

    @Test
    void resolveReadsTheDatabaseOnceThenServesTheCacheWithoutLosingTenantStatus() {
        TenantCache.put("T1", "Sky", TenantStatus.ACTIVE);
        when(subscriptionRepository.findByTenantId("T1"))
                .thenReturn(Optional.of(sub(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE).build()));

        assertThat(entitlementService.resolve("T1").skus()).isEqualTo(ALL);
        assertThat(entitlementService.resolve("T1").skus()).isEqualTo(ALL);

        verify(subscriptionRepository, times(1)).findByTenantId("T1");
        assertThat(TenantCache.status("T1")).contains(TenantStatus.ACTIVE);
        TenantCache.put("T1", "Sky Renamed", TenantStatus.SUSPENDED);
        assertThat(TenantCache.entitlements("T1")).isPresent(); // a status change keeps the entitlements
    }

    @Test
    void resolveRecomputesOnceTheCachedTrialOrWaiverHasEnded() {
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL, ALL, 5, false, PAST));
        when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.of(
                sub(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL).trialEndsAt(PAST).periodEnd(PAST).build()));

        assertThat(entitlementService.resolve("T1").readOnly()).isTrue();
    }

    @Test
    void refreshReplacesTheCachedValue() {
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, ALL, 5, false, null));
        when(subscriptionRepository.findByTenantId("T1"))
                .thenReturn(Optional.of(sub(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE).build()));

        entitlementService.refresh("T1");

        assertThat(TenantCache.entitlements("T1")).get().extracting(Entitlements::skus).isEqualTo(Set.of());
    }

    /** Rolling-deploy window: a tenant with no row yet gets a 14-day Suite trial, never a lockout. */
    @Test
    void missingRowStartsAFourteenDaySuiteTrial() {
        when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.empty());
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Entitlements e = entitlementService.resolve("T1");

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("T1");
        assertThat(saved.getValue().getPlan()).isEqualTo(SubscriptionPlan.SUITE);
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(Duration.between(Instant.now(), saved.getValue().getTrialEndsAt()).toDays()).isEqualTo(13);
        assertThat(e.skus()).isEqualTo(ALL);
        assertThat(e.readOnly()).isFalse();
        assertThat(TenantCache.entitlements("T1")).contains(e);
    }

    @Test
    void aConcurrentlyCreatedRowWinsOverOurTrial() {
        when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.empty(),
                Optional.of(sub(SubscriptionPlan.CUSTOM, SubscriptionStatus.ACTIVE).skus(List.of("VISA")).build()));
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThat(entitlementService.resolve("T1").skus()).isEqualTo(Set.of(Sku.VISA));
    }

    @Test
    void publicLinksNeedCrmCoreAndAWritableSubscription() {
        TenantCache.put("T1", "Sky", TenantStatus.ACTIVE);
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, ALL, 5, false, null));
        assertThat(entitlementService.publicLinksAvailable("T1")).isTrue();
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.READ_ONLY, ALL, 5, true, null));
        assertThat(entitlementService.publicLinksAvailable("T1")).isFalse();
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.CUSTOM, SubscriptionStatus.ACTIVE, Set.of(Sku.VISA), 0, false, null));
        assertThat(entitlementService.publicLinksAvailable("T1")).isFalse();
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE, Set.of(), 0, false, null));
        assertThat(entitlementService.publicLinksAvailable("T1")).isFalse();
    }

    @Test
    void publicLinksAreRefusedForASuspendedOrDeactivatedAgency() {
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, ALL, 5, false, null));
        TenantCache.put("T1", "Sky", TenantStatus.SUSPENDED);
        assertThat(entitlementService.publicLinksAvailable("T1")).isFalse();
        TenantCache.put("T1", "Sky", TenantStatus.DEACTIVATED);
        assertThat(entitlementService.publicLinksAvailable("T1")).isFalse();
    }

    @Test
    void aFailedTrialInsertWithNoRowAfterwardsIsRethrownNotStarter() {
        when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.empty());
        when(subscriptionRepository.saveAndFlush(any(Subscription.class))).thenThrow(new DataIntegrityViolationException("db down"));

        assertThatThrownBy(() -> entitlementService.resolve("T1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(TenantCache.entitlements("T1")).isEmpty();
    }

    @Test
    void assertWritableRefusesSuspendedWith423AndReadOnlyWith402() {
        TenantCache.put("T1", "Sky", TenantStatus.ACTIVE);
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, ALL, 5, false, null));
        entitlementService.assertWritable("T1");

        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.READ_ONLY, ALL, 5, true, null));
        assertThatThrownBy(() -> entitlementService.assertWritable("T1"))
                .isInstanceOfSatisfying(TenantWriteRefusedException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(402);
                    assertThat(e.getCode()).isEqualTo("READ_ONLY");
                });

        TenantCache.put("T1", "Sky", TenantStatus.SUSPENDED);
        assertThatThrownBy(() -> entitlementService.assertWritable("T1"))
                .isInstanceOfSatisfying(TenantWriteRefusedException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(423);
                    assertThat(e.getCode()).isEqualTo("TENANT_SUSPENDED");
                });
    }

    private static Subscription.SubscriptionBuilder sub(SubscriptionPlan plan, SubscriptionStatus status) {
        return Subscription.builder().id("S1").tenantId("T1").plan(plan).status(status)
                .skus(List.of()).seats(0).billingCycle(BillingCycle.MONTHLY).updatedAt(NOW);
    }

    private static Subscription waived(WaivedReason reason, Instant end, SubscriptionStatus status) {
        return sub(SubscriptionPlan.SUITE, status).waivedReason(reason).periodEnd(end).build();
    }

    @Test
    void starterIsNeverReadOnlyEvenWithAStaleTrialRow() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.STARTER, SubscriptionStatus.TRIAL).trialEndsAt(PAST).periodEnd(PAST).build(), NOW);
        assertThat(e.readOnly()).isFalse();
        assertThat(e.accessEndsAt()).isNull();
        assertThat(EntitlementService.compute(sub(SubscriptionPlan.STARTER, SubscriptionStatus.READ_ONLY).build(), NOW).readOnly()).isFalse();
    }

    @Test
    void cachedValueOlderThanTwoMinutesIsRecomputed() {
        Entitlements stale = new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, ALL, 5, false, null);
        TenantCache.putEntitlements("T1", stale);
        when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.of(sub(SubscriptionPlan.STARTER, SubscriptionStatus.ACTIVE).build()));
        assertThat(entitlementService.resolve("T1")).isSameAs(stale);  // fresh: no reload
        TenantCache.backdateEntitlements("T1", Duration.ofMinutes(3));
        assertThat(entitlementService.resolve("T1").plan()).isEqualTo(SubscriptionPlan.STARTER);
    }
}
