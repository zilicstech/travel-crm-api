package com.voyra.crm.service;

import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.util.ByteArrayMultipartFile;
import com.voyra.crm.util.SubscriptionInvoicePdfRenderer;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Voyra's subscription invoices (spec §5.2, §7.6): lines priced from {@code sku_prices}, GST 18 %, numbered
 * {@code VOY-SUB-<yyyy>-<seq>}, a stored PDF; settled by the gateway webhook or by an admin (bank), or voided.
 * Paying moves the agency onto a paid plan (waiver cleared, ACTIVE, the paid period) and refreshes the
 * entitlement cache in the same method. Never touches a wallet: subscriptions are billed outside it.
 */
@Slf4j
@Service
public class SubscriptionBillingService {

    /** Spec §5.2 "annual = 10 months": an annual plan is billed 10 monthly prices and runs 12 months. */
    public static final int ANNUAL_MONTHS_BILLED = 10;
    /** GST on Voyra's own service. */
    static final BigDecimal GST_RATE = new BigDecimal("0.18");
    /** Payment terms: due 7 days after the period starts (or after issue, when raised late); grace runs from here. */
    static final Duration DUE_AFTER = Duration.ofDays(7);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String SERIES = "VOY-SUB";
    public static final String ISSUED = "ISSUED";
    public static final String PAID = "PAID";
    public static final String VOID = "VOID";
    private static final String DRAFT = "DRAFT";
    private static final String GATEWAY = "GATEWAY";
    private static final String BANK = "BANK";
    private static final String REF_TYPE = "SUBSCRIPTION_INVOICE";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final TenantRepository tenantRepository;
    private final PlatformSettingService settingService;
    private final EntitlementService entitlementService;
    private final PlatformAuditService auditService;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;
    /** For work that must commit before object storage runs (blueprint §8.6: no storage inside a transaction). */
    private final TransactionTemplate tx;

    public SubscriptionBillingService(SubscriptionRepository subscriptionRepository, SubscriptionInvoiceRepository invoiceRepository,
                                      TenantRepository tenantRepository, PlatformSettingService settingService,
                                      EntitlementService entitlementService, PlatformAuditService auditService,
                                      NotificationService notificationService, FileStorageService fileStorageService,
                                      JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.tenantRepository = tenantRepository;
        this.settingService = settingService;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.fileStorageService = fileStorageService;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Not a DTO - unpacked into a binary response by the controller. */
    public record InvoicePdf(Resource resource, String fileName) {
    }

    // ---- arithmetic

    /** One line per SKU (Suite: the Suite price incl. 5 seats, plus extra seats); money as plain strings so JSONB keeps it exact. */
    public static List<Map<String, Object>> lines(Subscription s, Map<String, BigDecimal> prices) {
        int months = s.getBillingCycle() == BillingCycle.ANNUAL ? ANNUAL_MONTHS_BILLED : 1;
        int seats = s.getSeats() == null ? 0 : s.getSeats();
        List<Map<String, Object>> lines = new ArrayList<>();
        if (s.getPlan() == SubscriptionPlan.SUITE) {
            lines.add(line("SUITE", "Agency Suite (" + EntitlementService.SUITE_INCLUDED_SEATS + " staff seats included)", 1, prices, months));
            if (seats > 0) {
                lines.add(line(Sku.STAFF_SEATS.name(), "Extra staff seats", seats, prices, months));
            }
        } else if (s.getPlan() == SubscriptionPlan.CUSTOM && s.getSkus() != null) {
            for (Sku sku : Sku.values()) {
                if (!s.getSkus().contains(sku.name())) {
                    continue;
                }
                if (sku != Sku.STAFF_SEATS) {
                    lines.add(line(sku.name(), label(sku), 1, prices, months));
                } else if (seats > 0) {
                    lines.add(line(sku.name(), label(sku), seats, prices, months));
                }
            }
        }
        return lines;
    }

    static BigDecimal gst(BigDecimal amount) {
        return amount.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    static Instant periodEnd(Instant start, BillingCycle cycle) {
        return start.atZone(INDIA).plusMonths(cycle == BillingCycle.ANNUAL ? 12 : 1).toInstant();
    }

    /** Where a paid period raised now starts: the next paid period, or the end of a running trial/waiver, else now. */
    static Instant nextPeriodStart(Subscription s, Instant now) {
        boolean paidPlan = s.getWaivedReason() == null
                && (s.getStatus() == SubscriptionStatus.ACTIVE || s.getStatus() == SubscriptionStatus.PAST_DUE);
        if (paidPlan && s.getPeriodEnd() != null) {
            return s.getPeriodEnd();
        }
        Instant end = s.getStatus() == SubscriptionStatus.TRIAL
                ? (s.getTrialEndsAt() != null ? s.getTrialEndsAt() : s.getPeriodEnd())
                : s.getWaivedReason() != null ? s.getPeriodEnd() : null;
        return end != null && end.isAfter(now) ? end : now;  // paying ahead never shortens a running trial or waiver
    }

    // ---- issuing

    /** Caller holds the subscription row lock inside a transaction. The number is allocated in that transaction (no gaps). */
    SubscriptionInvoice issue(Subscription s, Instant start, Instant now) {
        List<Map<String, Object>> lines = lines(s, settingService.skuPrices());
        BigDecimal amount = lines.stream().map(l -> new BigDecimal((String) l.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new IllegalStateException("Nothing to bill: the plan has no paid SKUs");
        }
        BigDecimal gst = gst(amount);
        return invoiceRepository.save(SubscriptionInvoice.builder()
                .id(UniqueIdResolver.resolve(invoiceRepository::existsById)).tenantId(s.getTenantId())
                .number(nextNumber(now)).periodStart(start).periodEnd(periodEnd(start, s.getBillingCycle()))
                .lines(lines).amount(amount).gst(gst).total(amount.add(gst)).status(ISSUED)
                .dueAt((start.isAfter(now) ? start : now).plus(DUE_AFTER)).createdAt(now).build());
    }

    /** Lifecycle job: the invoice for the period starting when the paid one ends - at most one per period, even after a void. */
    Optional<SubscriptionInvoice> raiseRenewal(Subscription s, Instant now) {
        if (invoiceRepository.existsByTenantIdAndPeriodStart(s.getTenantId(), s.getPeriodEnd())
                || lines(s, settingService.skuPrices()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(issue(s, s.getPeriodEnd(), now));
    }

    /** Platform "raise now". A founding agency (waiver without expiry) is never invoiced. */
    public SubscriptionInvoiceResponse raiseNow(String tenantId) {
        SubscriptionInvoice issued = tx.execute(status -> {
            Subscription s = subscriptionRepository.findByTenantIdForUpdate(tenantId)
                    .orElseThrow(() -> new NotFoundException("Agency subscription not found"));
            if (s.getWaivedReason() != null && s.getPeriodEnd() == null) {
                throw new IllegalStateException("This agency's fee is waived without expiry - nothing to invoice");
            }
            if (s.getPlan() == SubscriptionPlan.STARTER || s.getStatus() == SubscriptionStatus.CANCELLED) {
                throw new IllegalStateException("Set a paid plan before raising an invoice");
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant start = nextPeriodStart(s, now);
            if (invoiceRepository.existsByTenantIdAndPeriodStartAndStatusIn(tenantId, start, List.of(ISSUED, PAID))) {
                throw new IllegalStateException("An invoice for this period already exists");
            }
            SubscriptionInvoice inv = issue(s, start, now);
            auditService.record(PlatformAuditAction.SUBSCRIPTION_INVOICE_RAISE, REF_TYPE, inv.getId(),
                    Map.of("tenantId", tenantId, "number", inv.getNumber(), "total", inv.getTotal().toPlainString()));
            return inv;
        });
        ensurePdfQuietly(issued.getId());
        return toResponse(issued);
    }

    // ---- settling

    /**
     * Webhook capture (amount and currency already checked against the order). Idempotent by payment id: the same
     * payment again is a no-op; a different payment on a PAID invoice, or any payment on a VOID one, alerts and
     * changes nothing. Returns true only when this call paid the invoice.
     */
    @Transactional
    public boolean markPaid(String invoiceId, String paymentId) {
        SubscriptionInvoice inv = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Subscription invoice " + invoiceId + " not found"));
        if (PAID.equals(inv.getStatus())) {
            if (!(GATEWAY.equals(inv.getPaidVia()) && paymentId.equals(inv.getPaidRef()))) {
                notificationService.platformAlert("Second payment on a paid subscription invoice",
                        "Invoice " + inv.getNumber() + " (agency " + inv.getTenantId() + ") was already paid via " + inv.getPaidVia()
                                + " (" + inv.getPaidRef() + "); payment " + paymentId + " was not applied - refund it.");
            }
            return false;
        }
        if (!ISSUED.equals(inv.getStatus())) {
            notificationService.platformAlert("Payment on a void subscription invoice",
                    "Invoice " + inv.getNumber() + " (agency " + inv.getTenantId() + ") is " + inv.getStatus()
                            + "; payment " + paymentId + " was not applied - refund it.");
            return false;
        }
        settle(inv, GATEWAY, paymentId);
        return true;
    }

    @Transactional
    public SubscriptionInvoiceResponse markPaidByBank(String tenantId, String invoiceId, String reference) {
        SubscriptionInvoice inv = lockIssued(tenantId, invoiceId);
        String ref = reference.trim();
        auditService.record(PlatformAuditAction.SUBSCRIPTION_INVOICE_PAID, REF_TYPE, invoiceId,
                Map.of("tenantId", tenantId, "number", inv.getNumber(), "reference", ref, "total", inv.getTotal().toPlainString()));
        settle(inv, BANK, ref);
        return toResponse(inv);
    }

    /** An unpaid invoice only. PAST_DUE with nothing else overdue is ACTIVE again; READ_ONLY stays until paid, waived or re-raised. */
    @Transactional
    public SubscriptionInvoiceResponse voidInvoice(String tenantId, String invoiceId, String reason) {
        SubscriptionInvoice inv = lockIssued(tenantId, invoiceId);
        inv.setStatus(VOID);
        invoiceRepository.save(inv);
        auditService.record(PlatformAuditAction.SUBSCRIPTION_INVOICE_VOID, REF_TYPE, invoiceId,
                Map.of("tenantId", tenantId, "number", inv.getNumber(), "reason", reason.trim()));
        Subscription s = subscriptionRepository.findByTenantIdForUpdate(tenantId).orElse(null);
        if (s != null && s.getStatus() == SubscriptionStatus.PAST_DUE
                && !invoiceRepository.existsByTenantIdAndStatusAndDueAtLessThanEqual(tenantId, ISSUED, Instant.now())) {
            s.setStatus(SubscriptionStatus.ACTIVE);
            s.setUpdatedAt(Instant.now());
            subscriptionRepository.save(s);
            entitlementService.refresh(tenantId);
        }
        return toResponse(inv);
    }

    /** Paying moves onto a paid plan: waiver cleared, ACTIVE, the invoice's period (never moved backwards). */
    private void settle(SubscriptionInvoice inv, String via, String ref) {
        Instant now = Instant.now();
        inv.setStatus(PAID);
        inv.setPaidAt(now);
        inv.setPaidVia(via);
        inv.setPaidRef(ref);
        invoiceRepository.save(inv);
        Subscription s = subscriptionRepository.findByTenantIdForUpdate(inv.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Agency " + inv.getTenantId() + " has no subscription"));
        if (s.getWaivedReason() != null || s.getPeriodEnd() == null || inv.getPeriodEnd().isAfter(s.getPeriodEnd())) {
            s.setPeriodStart(inv.getPeriodStart());
            s.setPeriodEnd(inv.getPeriodEnd());
        }
        s.setWaivedReason(null);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setUpdatedAt(now);
        subscriptionRepository.save(s);
        entitlementService.refresh(inv.getTenantId());
    }

    // ---- reading

    @Transactional(readOnly = true)
    public List<SubscriptionInvoiceResponse> listForPlatform(String tenantId) {
        return invoiceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(SubscriptionBillingService::toResponse).toList();
    }

    /** Renders and stores the PDF on first request (outside any transaction), then serves the stored copy. */
    public InvoicePdf pdf(String tenantId, String invoiceId) {
        SubscriptionInvoice inv = invoiceRepository.findById(invoiceId)
                .filter(i -> i.getTenantId().equals(tenantId) && !DRAFT.equals(i.getStatus()))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        String key = inv.getPdfKey() != null ? inv.getPdfKey() : storePdf(inv);
        return new InvoicePdf(fileStorageService.retrieve(key), inv.getNumber() + ".pdf");
    }

    /** After an invoice is issued: a storage failure only defers the PDF to its first download. */
    void ensurePdfQuietly(String invoiceId) {
        try {
            invoiceRepository.findById(invoiceId).filter(i -> i.getPdfKey() == null).ifPresent(this::storePdf);
        } catch (Exception e) {
            log.warn("Subscription invoice {} PDF not stored yet ({}); it is rendered on first download", invoiceId, e.getMessage());
        }
    }

    private String storePdf(SubscriptionInvoice inv) {
        byte[] bytes = SubscriptionInvoicePdfRenderer.write(inv, tenantRepository.findById(inv.getTenantId()).orElse(null));
        String key = fileStorageService.store("platform", "subscription-invoices/" + inv.getTenantId(), inv.getId(),
                new ByteArrayMultipartFile(inv.getNumber() + ".pdf", "application/pdf", bytes));
        tx.executeWithoutResult(status -> invoiceRepository.setPdfKeyIfMissing(inv.getId(), key));
        return key;
    }

    public static SubscriptionInvoiceResponse toResponse(SubscriptionInvoice i) {
        return SubscriptionInvoiceResponse.builder().id(i.getId()).number(i.getNumber())
                .periodStart(i.getPeriodStart()).periodEnd(i.getPeriodEnd()).amount(i.getAmount()).gst(i.getGst())
                .total(i.getTotal()).status(i.getStatus()).dueAt(i.getDueAt()).paidAt(i.getPaidAt()).paidVia(i.getPaidVia())
                .build();
    }

    // ---- helpers

    private SubscriptionInvoice lockIssued(String tenantId, String invoiceId) {
        SubscriptionInvoice inv = invoiceRepository.findByIdForUpdate(invoiceId)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!ISSUED.equals(inv.getStatus())) {
            throw new IllegalStateException("This invoice is already " + inv.getStatus().toLowerCase(Locale.ROOT));
        }
        return inv;
    }

    /** Voyra's own series; the upsert row-locks (series, year) until the caller's transaction ends, so numbers never repeat or gap. */
    private String nextNumber(Instant now) {
        int year = now.atZone(INDIA).getYear();
        Long seq = jdbcTemplate.queryForObject("""
                INSERT INTO voyra_invoice_sequence (series, year, last_value, updated_at) VALUES (?, ?, 1, now())
                ON CONFLICT (series, year) DO UPDATE SET last_value = voyra_invoice_sequence.last_value + 1, updated_at = now()
                RETURNING last_value
                """, Long.class, SERIES, year);
        return SERIES + "-" + year + "-" + seq;
    }

    private static Map<String, Object> line(String sku, String description, int quantity, Map<String, BigDecimal> prices, int months) {
        BigDecimal unit = prices.get(sku);
        if (unit == null) {
            throw new IllegalStateException("No price for " + sku + " in the sku_prices setting");
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sku", sku);
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", unit.setScale(2, RoundingMode.HALF_UP).toPlainString());
        line.put("months", months);
        line.put("amount", unit.multiply(BigDecimal.valueOf((long) quantity * months)).setScale(2, RoundingMode.HALF_UP).toPlainString());
        return line;
    }

    private static String label(Sku sku) {
        return switch (sku) {
            case CRM_CORE -> "CRM Core";
            case STAFF_SEATS -> "Staff seats";
            case ACCOUNTS_GST -> "Accounts & GST";
            case REPORTS -> "Reports";
            case VISA -> "Visa desk";
        };
    }
}
