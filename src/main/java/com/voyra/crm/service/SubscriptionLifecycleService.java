package com.voyra.crm.service;

import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The daily subscription run (spec §5.2, §7.6), one tenant per transaction with its row locked:
 * TRIAL or waiver end → READ_ONLY directly; a paid plan at period end gets its renewal invoice; an ISSUED invoice
 * past due → PAST_DUE, 7 days later → READ_ONLY; founding agencies (waiver without expiry) are never touched.
 * 14/7/1-day expiry notices go once each. Every status change refreshes the entitlement cache.
 */
@Slf4j
@Service
public class SubscriptionLifecycleService {

    /** Spec §5.2: "grace 7 days after due, then READ_ONLY". */
    static final Duration GRACE = Duration.ofDays(7);
    /** Ascending: the notice due is the smallest threshold not below the days left. */
    static final List<Integer> NOTICE_DAYS = List.of(1, 7, 14);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final SubscriptionBillingService billingService;
    private final EntitlementService entitlementService;
    private final NotificationService notificationService;
    private final TenantRepository tenantRepository;
    private final TransactionTemplate tx;

    public SubscriptionLifecycleService(SubscriptionRepository subscriptionRepository, SubscriptionInvoiceRepository invoiceRepository,
                                        SubscriptionBillingService billingService, EntitlementService entitlementService,
                                        NotificationService notificationService, PlatformTransactionManager transactionManager,
                                        TenantRepository tenantRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.billingService = billingService;
        this.entitlementService = entitlementService;
        this.notificationService = notificationService;
        this.tenantRepository = tenantRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void runDaily() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int failed = 0;
        List<String> tenantIds = subscriptionRepository.findAllTenantIds();
        for (String tenantId : tenantIds) {
            try {
                String raised = tx.execute(status -> processTenant(tenantId, now));
                if (raised != null) {
                    billingService.ensurePdfQuietly(raised);  // after the commit: storage never inside a transaction
                }
            } catch (Exception e) {
                failed++;
                log.error("Subscription lifecycle failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("Subscription lifecycle checked {} agency(ies), {} failed", tenantIds.size(), failed);
    }

    /** One tenant, inside the caller's transaction. Returns the id of a renewal invoice raised, if any. */
    String processTenant(String tenantId, Instant now) {
        Subscription s = subscriptionRepository.findByTenantIdForUpdate(tenantId).orElse(null);
        if (s == null || s.getStatus() == SubscriptionStatus.CANCELLED || s.getPlan() == SubscriptionPlan.STARTER
                || (s.getWaivedReason() != null && s.getPeriodEnd() == null)) {
            return null;  // founding agency: no expiry, no invoice, never READ_ONLY
        }
        if (tenantRepository.findById(tenantId).map(t -> t.getStatus() == TenantStatus.DEACTIVATED).orElse(false)) {
            return null;  // a deactivated agency is billed and told nothing
        }
        SubscriptionStatus before = s.getStatus();
        boolean noticed = false;
        String raised = null;
        if (s.getStatus() == SubscriptionStatus.READ_ONLY) {
            return null;  // until paid, waived or given a new trial
        } else if (s.getWaivedReason() != null) {
            if (now.isBefore(s.getPeriodEnd())) {
                noticed = notice(s, "WAIVER", s.getPeriodEnd(), now);
            } else {
                s.setStatus(SubscriptionStatus.READ_ONLY);  // no invoice exists to be past due
            }
        } else if (s.getStatus() == SubscriptionStatus.TRIAL) {
            Instant end = s.getTrialEndsAt() != null ? s.getTrialEndsAt() : s.getPeriodEnd();
            if (end != null && now.isBefore(end)) {
                noticed = notice(s, "TRIAL", end, now);
            } else {
                s.setStatus(SubscriptionStatus.READ_ONLY);
            }
        } else {
            if (s.getPeriodEnd() != null && !now.isBefore(s.getPeriodEnd())) {
                raised = billingService.raiseRenewal(s, now).map(SubscriptionInvoice::getId).orElse(null);
            }
            Instant due = invoiceRepository.findFirstByTenantIdAndStatusOrderByDueAtAsc(tenantId, SubscriptionBillingService.ISSUED)
                    .map(SubscriptionInvoice::getDueAt).orElse(null);
            if (due != null && !now.isBefore(due.plus(GRACE))) {
                s.setStatus(SubscriptionStatus.READ_ONLY);
            } else if (due != null && !now.isBefore(due)) {
                s.setStatus(SubscriptionStatus.PAST_DUE);
            } else {
                s.setStatus(SubscriptionStatus.ACTIVE);  // nothing overdue any more (paid or voided elsewhere)
            }
            if (s.getPeriodEnd() != null && now.isBefore(s.getPeriodEnd())) {
                noticed = notice(s, "PERIOD", s.getPeriodEnd(), now);
            }
        }
        boolean statusChanged = s.getStatus() != before;
        if (statusChanged || noticed) {
            s.setUpdatedAt(now);
            subscriptionRepository.save(s);
        }
        if (statusChanged) {
            entitlementService.refresh(tenantId);
        }
        return raised;
    }

    /** Sends the 14/7/1-day notice due for {@code end}, once: the smallest threshold sent is kept per end date. */
    private boolean notice(Subscription s, String kind, Instant end, Instant now) {
        int daysLeft = (int) Math.ceil(Duration.between(now, end).toMinutes() / 1440.0);
        Integer due = NOTICE_DAYS.stream().filter(d -> daysLeft <= d).findFirst().orElse(null);
        if (due == null || (end.equals(s.getExpiryNoticeFor()) && s.getExpiryNoticeDays() != null && s.getExpiryNoticeDays() <= due)) {
            return false;
        }
        notificationService.subscriptionExpiring(s.getTenantId(), kind, end, daysLeft);
        s.setExpiryNoticeDays(due);
        s.setExpiryNoticeFor(end);
        return true;
    }
}
