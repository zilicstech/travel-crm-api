package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.exception.TenantWriteRefusedException;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves what an agency may use from its subscription (spec §8), cached in TenantCache.
 * Every method that changes a subscription row must call {@link #refresh} (or put the computed
 * value) in the same method, so the cache is never stale.
 */
@Slf4j
@Service
public class EntitlementService {

    public static final int SUITE_INCLUDED_SEATS = 5;
    public static final int LITE_INCLUDED_SEATS = 3;
    /** CRM Lite: everything except Accounts and GST. */
    public static final Set<Sku> LITE_SKUS = java.util.Collections.unmodifiableSet(EnumSet.of(Sku.CRM_CORE, Sku.STAFF_SEATS, Sku.REPORTS, Sku.VISA));

    /** The SKU names stored for a fixed plan (Suite: every SKU, Lite: LITE_SKUS, Starter: none); CUSTOM keeps its own list. */
    public static List<String> planSkus(SubscriptionPlan plan) {
        return switch (plan) {
            case SUITE -> Arrays.stream(Sku.values()).map(Enum::name).toList();
            case LITE -> LITE_SKUS.stream().map(Enum::name).toList();
            case STARTER, CUSTOM -> List.of();
        };
    }
    static final Duration CACHE_TTL = Duration.ofMinutes(2);
    public static final Duration TRIAL_LENGTH = Duration.ofDays(14);

    private final SubscriptionRepository subscriptionRepository;
    /** Its own transaction: resolve() is called from read-only transactions (login, public links). */
    private final TransactionTemplate newTransaction;

    public EntitlementService(SubscriptionRepository subscriptionRepository, PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Cached value while it still holds; a trial or waiver that has since ended is recomputed. */
    public Entitlements resolve(String tenantId) {
        Optional<Entitlements> cached = TenantCache.entitlements(tenantId);
        // The TTL bounds staleness when another node (or a direct DB change) altered the subscription.
        boolean young = TenantCache.entitlementsAt(tenantId).map(at -> at.isAfter(Instant.now().minus(CACHE_TTL))).orElse(false);
        if (cached.isPresent() && young && (cached.get().accessEndsAt() == null || Instant.now().isBefore(cached.get().accessEndsAt()))) {
            return cached.get();
        }
        return refresh(tenantId);
    }

    /**
     * Public proposal and feedback links are CRM Core features: an agency without CRM Core, READ_ONLY,
     * or not ACTIVE (SUSPENDED, DEACTIVATED), gets "This link is no longer available" instead (spec §8).
     */
    public boolean publicLinksAvailable(String tenantId) {
        if (TenantCache.status(tenantId).orElse(null) != TenantStatus.ACTIVE) {
            return false;
        }
        Entitlements e = resolve(tenantId);
        return e.skus().contains(Sku.CRM_CORE) && !e.readOnly();
    }

    /** For writes the interceptors pass through on purpose (staff reactivation): SUSPENDED 423, READ_ONLY 402. */
    public void assertWritable(String tenantId) {
        if (TenantCache.status(tenantId).orElse(null) == TenantStatus.SUSPENDED) {
            throw TenantWriteRefusedException.suspended();
        }
        if (resolve(tenantId).readOnly()) {
            throw TenantWriteRefusedException.readOnly();
        }
    }

    /** Reloads from the subscription row and replaces the cached value. */
    public Entitlements refresh(String tenantId) {
        Subscription s = subscriptionRepository.findByTenantId(tenantId).orElseGet(() -> startMissingTrial(tenantId));
        Entitlements e = compute(s, Instant.now());
        TenantCache.putEntitlements(tenantId, e);
        return e;
    }

    /** Every new agency starts on Voyra Basic (owner ruling 2026-10-30: no free Premium trial); it upgrades from Plan & Billing. */
    public static Subscription newBasic(String id, String tenantId, Instant now) {
        return Subscription.builder()
                .id(id)
                .tenantId(tenantId)
                .plan(SubscriptionPlan.STARTER)
                .skus(List.of())
                .seats(0)
                .status(SubscriptionStatus.ACTIVE)
                .periodStart(now)
                .billingCycle(BillingCycle.MONTHLY)
                .updatedAt(now)
                .build();
    }

    /**
     * Rolling-deploy window: a tenant created before its subscription row existed must never be locked
     * out, so it gets Voyra Basic, as a new agency does. A concurrent request creating the same row wins (tenant_id is unique).
     */
    public Subscription startMissingTrial(String tenantId) {
        log.warn("Tenant {} has no subscription row - starting it on Voyra Basic", tenantId);
        try {
            return newTransaction.execute(tx -> subscriptionRepository.saveAndFlush(
                    newBasic(UniqueIdResolver.resolve(subscriptionRepository::existsById), tenantId, Instant.now())));
        } catch (DataAccessException e) {
            // A concurrent create wins; anything else is a real failure, never a silent Starter downgrade.
            return subscriptionRepository.findByTenantId(tenantId).orElseThrow(() -> e);
        }
    }

    /**
     * Pure resolution. An unexpired waiver always grants its plan (founding agencies are never
     * READ_ONLY); a trial or waiver past its end is READ_ONLY even before the daily job flips the
     * status; CANCELLED and a missing row are Starter.
     */
    public static Entitlements compute(Subscription s, Instant now) {
        // Starter is the portal-only floor: never read-only, whatever stale trial or status the row carries.
        if (s == null || s.getStatus() == SubscriptionStatus.CANCELLED || s.getPlan() == SubscriptionPlan.STARTER) {
            return new Entitlements(SubscriptionPlan.STARTER, s == null ? null : s.getStatus(), Set.of(), 0, false, null);
        }
        Instant waiverEnd = s.getPeriodEnd();
        boolean waived = s.getWaivedReason() != null && (waiverEnd == null || now.isBefore(waiverEnd));
        Instant trialEnd = s.getTrialEndsAt() != null ? s.getTrialEndsAt() : s.getPeriodEnd();
        boolean trialRunning = s.getStatus() == SubscriptionStatus.TRIAL && trialEnd != null && now.isBefore(trialEnd);
        boolean expired = (s.getWaivedReason() != null && !waived)
                || (s.getStatus() == SubscriptionStatus.TRIAL && !trialRunning);
        boolean readOnly = !waived && (expired || s.getStatus() == SubscriptionStatus.READ_ONLY);
        Instant accessEndsAt = waived ? waiverEnd : trialRunning && !readOnly ? trialEnd : null;

        int extra = s.getSeats() == null ? 0 : s.getSeats();
        Set<Sku> skus = switch (s.getPlan()) {
            case SUITE -> EnumSet.allOf(Sku.class);
            case LITE -> LITE_SKUS;
            // STARTER returned above; it shares CUSTOM's arm only so a new plan still fails to compile here.
            case CUSTOM, STARTER -> parse(s.getSkus());
        };
        int seats = switch (s.getPlan()) {
            case SUITE -> SUITE_INCLUDED_SEATS + extra;
            case LITE -> LITE_INCLUDED_SEATS + extra;
            case CUSTOM, STARTER -> skus.contains(Sku.STAFF_SEATS) ? extra : 0;
        };
        return new Entitlements(s.getPlan(), s.getStatus(), skus, seats, readOnly, accessEndsAt);
    }

    /** Unknown names are skipped, so a renamed SKU never breaks resolution. */
    private static Set<Sku> parse(List<String> names) {
        if (names == null) {
            return Set.of();
        }
        Set<String> known = Arrays.stream(Sku.values()).map(Enum::name).collect(Collectors.toSet());
        return names.stream().filter(known::contains).map(Sku::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Sku.class)));
    }
}
