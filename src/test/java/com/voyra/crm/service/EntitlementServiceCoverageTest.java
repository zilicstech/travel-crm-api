package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceCoverageTest {

    private static final Instant NOW = Instant.now();

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private EntitlementService entitlementService;

    @AfterEach
    void forget() {
        TenantCache.remove("T-ENT-COV");
    }

    private static Subscription.SubscriptionBuilder sub(SubscriptionPlan plan, SubscriptionStatus status) {
        return Subscription.builder().id("S1").tenantId("T1").plan(plan).status(status);
    }

    @Test
    void aCachedTrialStillRunningIsServedFromTheCache() {
        Entitlements trial = new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL, EnumSet.allOf(Sku.class), 5, false,
                NOW.plus(Duration.ofDays(3)));
        TenantCache.putEntitlements("T-ENT-COV", trial);

        assertThat(entitlementService.resolve("T-ENT-COV")).isSameAs(trial);
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void missingExtraSeatsCountAsNone() {
        assertThat(EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE).seats(null).build(), NOW).seats()).isEqualTo(5);
    }

    @Test
    void aCustomPlanWithNoSkuListHasNothing() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.CUSTOM, SubscriptionStatus.ACTIVE).skus(null).seats(4).build(), NOW);
        assertThat(e.skus()).isEmpty();
        assertThat(e.seats()).isZero();
    }

    @Test
    void aTrialWithNoEndDateIsOverAndReadOnly() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL).trialEndsAt(null).periodEnd(null).build(), NOW);
        assertThat(e.readOnly()).isTrue();
        assertThat(e.accessEndsAt()).isNull();
    }

    @Test
    void aRunningTrialUnderAnEndedWaiverIsReadOnlyWithNoAccessEnd() {
        Entitlements e = EntitlementService.compute(sub(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL)
                .waivedReason(WaivedReason.COMPLIMENTARY).periodEnd(NOW.minus(Duration.ofDays(1)))
                .trialEndsAt(NOW.plus(Duration.ofDays(5))).build(), NOW);
        assertThat(e.readOnly()).isTrue();
        assertThat(e.accessEndsAt()).isNull();
    }
}
