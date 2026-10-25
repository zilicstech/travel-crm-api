package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySubscriptionResponse;
import com.voyra.crm.dto.SubscriptionAdminResponse;
import com.voyra.crm.dto.SubscriptionUpdateRequest;
import com.voyra.crm.dto.SubscriptionWaiveRequest;
import com.voyra.crm.dto.TrialExtendRequest;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Subscription changes by the platform admin and the agency's own read view. Every change saves the row,
 * refreshes the entitlement cache in the same method, and is audited.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Map<WaivedReason, String> WAIVER_LABELS = Map.of(
            WaivedReason.FOUNDING_AGENCY, "Founding agency",
            WaivedReason.COMPLIMENTARY, "Complimentary",
            WaivedReason.GMV_THRESHOLD, "Booking-volume benefit");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final EntitlementService entitlementService;
    private final PlatformAuditService auditService;

    @Transactional(readOnly = true)
    public SubscriptionAdminResponse adminView(String tenantId) {
        return adminView(load(tenantId), entitlementService.resolve(tenantId));
    }

    private SubscriptionAdminResponse adminView(Subscription s, Entitlements e) {
        String tenantId = s.getTenantId();
        return SubscriptionAdminResponse.builder()
                .plan(e.plan()).skus(e.skus()).extraSeats(s.getSeats()).seatsTotal(e.seats())
                .seatsUsed(agentRepository.countByTenantIdAndIsActiveTrue(tenantId))
                .status(s.getStatus()).billingCycle(s.getBillingCycle()).periodStart(s.getPeriodStart())
                .periodEnd(s.getPeriodEnd()).trialEndsAt(s.getTrialEndsAt()).waivedReason(s.getWaivedReason())
                .readOnly(e.readOnly()).build();
    }

    @Transactional(readOnly = true)
    public AgencySubscriptionResponse agencyView() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("No tenant context for current user");
        }
        Subscription s = load(tenantId);
        Entitlements e = entitlementService.resolve(tenantId);
        // the waiver reason text stays internal: the agency sees only that it is waived, with a friendly label
        boolean waived = s.getWaivedReason() != null && !EntitlementService.compute(s, Instant.now()).readOnly();
        return AgencySubscriptionResponse.builder()
                .plan(e.plan()).skus(e.skus()).seatsTotal(e.seats())
                .seatsUsed(agentRepository.countByTenantIdAndIsActiveTrue(tenantId))
                .status(s.getStatus()).billingCycle(s.getBillingCycle()).periodStart(s.getPeriodStart())
                .periodEnd(s.getPeriodEnd()).trialEndsAt(s.getTrialEndsAt()).readOnly(e.readOnly())
                .waived(waived).waivedLabel(waived ? WAIVER_LABELS.get(s.getWaivedReason()) : null)
                .invoices(invoiceRepository.findByTenantIdAndStatusNotOrderByCreatedAtDesc(tenantId, "DRAFT").stream()
                        .map(SubscriptionBillingService::toResponse).toList())
                .build();
    }

    /** Plan, SKUs, seats and cycle. A waiver is a separate switch and stays as it is. */
    @Transactional
    public SubscriptionAdminResponse update(String tenantId, SubscriptionUpdateRequest request) {
        if (request.getSeats() == null || request.getSeats() < 0) {
            throw new IllegalArgumentException("seats cannot be negative");
        }
        if (request.getPlan() == SubscriptionPlan.CUSTOM && request.getSeats() > 0
                && (request.getSkus() == null || !request.getSkus().contains(Sku.STAFF_SEATS))) {
            throw new IllegalArgumentException("Add the Staff seats SKU to give seats");
        }
        Subscription s = load(tenantId);
        SubscriptionPlan plan = request.getPlan();
        List<String> skus = switch (plan) {
            case SUITE -> Arrays.stream(Sku.values()).map(Enum::name).toList();
            case CUSTOM -> request.getSkus() == null ? List.<String>of()
                    : request.getSkus().stream().distinct().map(Enum::name).toList();
            case STARTER -> List.of();
        };
        Map<String, Object> before = Map.of("plan", s.getPlan().name(), "skus", s.getSkus(), "seats", s.getSeats(),
                "billingCycle", s.getBillingCycle().name());
        s.setPlan(plan);
        s.setSkus(skus);
        s.setSeats(plan == SubscriptionPlan.STARTER ? 0 : request.getSeats());
        s.setBillingCycle(request.getBillingCycle());
        s.setUpdatedAt(Instant.now());
        subscriptionRepository.save(s);
        auditService.record(PlatformAuditAction.SUBSCRIPTION_CHANGE, "TENANT", tenantId, Map.of("before", before,
                "after", Map.of("plan", plan.name(), "skus", skus, "seats", s.getSeats(), "billingCycle", s.getBillingCycle().name())));
        Entitlements fresh = entitlementService.refresh(tenantId);
        return adminView(s, fresh);
    }

    /** No fee until {@code until} (null = no expiry): status ACTIVE, so a read-only agency is writable again. */
    @Transactional
    public SubscriptionAdminResponse waive(String tenantId, SubscriptionWaiveRequest request) {
        if (request.getUntil() != null && !request.getUntil().isAfter(Instant.now())) {
            throw new IllegalArgumentException("until must be in the future");
        }
        Subscription s = load(tenantId);
        if (s.getPlan() == SubscriptionPlan.STARTER || s.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new IllegalArgumentException("Set a plan before waiving");
        }
        Instant now = Instant.now();
        s.setWaivedReason(request.getReason());
        s.setPeriodEnd(request.getUntil());
        s.setPeriodStart(s.getPeriodStart() == null ? now : s.getPeriodStart());
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setUpdatedAt(now);
        subscriptionRepository.save(s);
        auditService.record(PlatformAuditAction.SUBSCRIPTION_WAIVE, "TENANT", tenantId, request.getUntil() == null
                ? Map.of("reason", request.getReason().name()) : Map.of("reason", request.getReason().name(), "until", request.getUntil().toString()));
        Entitlements fresh = entitlementService.refresh(tenantId);
        return adminView(s, fresh);
    }

    /** Back to a Suite trial ending {@code days} after the current end (or after now when it has ended); a trial is not a waiver. */
    @Transactional
    public SubscriptionAdminResponse extendTrial(String tenantId, TrialExtendRequest request) {
        Subscription s = load(tenantId);
        Instant now = Instant.now();
        Instant base = s.getStatus() == SubscriptionStatus.TRIAL && s.getTrialEndsAt() != null && s.getTrialEndsAt().isAfter(now)
                ? s.getTrialEndsAt() : now;
        Instant end = base.plus(Duration.ofDays(request.getDays()));
        s.setPlan(SubscriptionPlan.SUITE);
        s.setSkus(Arrays.stream(Sku.values()).map(Enum::name).toList());
        s.setStatus(SubscriptionStatus.TRIAL);
        s.setWaivedReason(null);
        s.setPeriodStart(s.getPeriodStart() == null ? now : s.getPeriodStart());
        s.setTrialEndsAt(end);
        s.setPeriodEnd(end);
        s.setUpdatedAt(now);
        subscriptionRepository.save(s);
        auditService.record(PlatformAuditAction.SUBSCRIPTION_TRIAL_EXTEND, "TENANT", tenantId,
                Map.of("days", request.getDays(), "trialEndsAt", end.toString()));
        Entitlements fresh = entitlementService.refresh(tenantId);
        return adminView(s, fresh);
    }

    private Subscription load(String tenantId) {
        return subscriptionRepository.findByTenantId(tenantId).orElseGet(() -> {
            if (!tenantRepository.existsById(tenantId)) {
                throw new NotFoundException("Agency not found");
            }
            return entitlementService.startMissingTrial(tenantId);
        });
    }
}
