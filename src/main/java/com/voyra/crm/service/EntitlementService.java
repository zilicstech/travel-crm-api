package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
@Service
@RequiredArgsConstructor
public class EntitlementService {

    public static final int SUITE_INCLUDED_SEATS = 5;

    private final SubscriptionRepository subscriptionRepository;

    /** Cached value while it still holds; a trial or waiver that has since ended is recomputed. */
    public Entitlements resolve(String tenantId) {
        Optional<Entitlements> cached = TenantCache.entitlements(tenantId);
        if (cached.isPresent() && (cached.get().accessEndsAt() == null || Instant.now().isBefore(cached.get().accessEndsAt()))) {
            return cached.get();
        }
        return refresh(tenantId);
    }

    /** Reloads from the subscription row and replaces the cached value. */
    public Entitlements refresh(String tenantId) {
        Entitlements e = compute(subscriptionRepository.findByTenantId(tenantId).orElse(null), Instant.now());
        TenantCache.putEntitlements(tenantId, e);
        return e;
    }

    /**
     * Pure resolution. An unexpired waiver always grants its plan (founding agencies are never
     * READ_ONLY); a trial or waiver past its end is READ_ONLY even before the daily job flips the
     * status; CANCELLED and a missing row are Starter.
     */
    public static Entitlements compute(Subscription s, Instant now) {
        if (s == null || s.getStatus() == SubscriptionStatus.CANCELLED) {
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
            case CUSTOM -> parse(s.getSkus());
            case STARTER -> Set.of();
        };
        int seats = switch (s.getPlan()) {
            case SUITE -> SUITE_INCLUDED_SEATS + extra;
            case CUSTOM -> extra;
            case STARTER -> 0;
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
