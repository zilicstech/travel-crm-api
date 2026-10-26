package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingCreateRequest;
import com.voyra.crm.dto.AgencyPricingResponse;
import com.voyra.crm.dto.PricingPreviewResponse;
import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.AgencyPricingRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PricingMath;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-agency discount and commission rates. Any platform admin sets a rate and it is approved at once
 * by them; the worst-case margin over the reference fare basket is stored and audited, so the audit log
 * shows who set a loss-making rate.
 */
@Service
@RequiredArgsConstructor
public class AgencyPricingService {

    private static final java.time.format.DateTimeFormatter SHOWN_AT = java.time.format.DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm 'IST'", java.util.Locale.ENGLISH).withZone(java.time.ZoneId.of("Asia/Kolkata"));

    private static final String ENTITY_TYPE = "AGENCY_PRICING";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AgencyPricingRepository repository;
    private final TenantRepository tenantRepository;
    private final PlatformSettingService settings;
    private final PlatformAuditService auditService;

    @Transactional(readOnly = true)
    public PricingPreviewResponse preview(String tenantId, PricingProduct product, BigDecimal discountPct,
                                          BigDecimal commissionPct, boolean allowCommissionAboveEarned) {
        requireAgency(tenantId);
        requirePct("Discount", discountPct);
        requirePct("Commission", commissionPct);
        // shortcut: voyraEarnedPct is the platform estimate for every product until slice 3 brings API/statement figures
        BigDecimal earned = settings.commissionEstimatePct();
        DiscountBase db = settings.discountBase();
        List<ReferenceFare> basket = settings.referenceFareBasket(product);
        BigDecimal worst = PricingMath.worstCaseNetPer10k(basket, discountPct, commissionPct, earned, db, allowCommissionAboveEarned);
        List<PricingPreviewResponse.BasketLine> lines = basket.stream().map(f -> PricingPreviewResponse.BasketLine.builder()
                .label(f.label())
                .displayed(PricingMath.displayed(f.gross(), f.base(), discountPct, db))
                .voyraNet(PricingMath.voyraNet(f.gross(), f.base(), discountPct, commissionPct, earned, db, allowCommissionAboveEarned))
                .build()).toList();
        return PricingPreviewResponse.builder()
                .worstCaseNetPer10k(worst)
                .basketLines(lines)
                .build();
    }

    @Transactional
    public AgencyPricingResponse create(String tenantId, AgencyPricingCreateRequest request) {
        requireAgency(tenantId);
        Instant now = Instant.now();
        // Postgres keeps microseconds: truncate so the duplicate check sees the value that will be stored
        Instant from = request.getEffectiveFrom().truncatedTo(ChronoUnit.MICROS);
        // one minute of slack for clock skew between the browser and the server
        if (from.isBefore(now.minus(1, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Effective date must be now or later");
        }
        if (repository.existsByTenantIdAndProductAndEffectiveFrom(tenantId, request.getProduct(), from)) {
            throw new IllegalStateException("A " + request.getProduct() + " rate already starts at " + SHOWN_AT.format(from) + " for this agency");
        }
        // stored as NUMERIC(5,2): guard on the value that will actually be saved
        BigDecimal d = request.getDiscountPct().setScale(2, RoundingMode.HALF_UP);
        BigDecimal c = request.getCommissionPct().setScale(2, RoundingMode.HALF_UP);
        BigDecimal worst = PricingMath.worstCaseNetPer10k(settings.referenceFareBasket(request.getProduct()), d, c,
                settings.commissionEstimatePct(), settings.discountBase(), request.isAllowCommissionAboveEarned());
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        AgencyPricing saved = repository.save(AgencyPricing.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .tenantId(tenantId)
                .product(request.getProduct())
                .discountPct(d)
                .commissionPct(c)
                .allowCommissionAboveEarned(request.isAllowCommissionAboveEarned())
                .effectiveFrom(from)
                .worstCaseNetPer10k(worst)
                .requiresSecondApproval(false)
                .createdBy(actor)
                .createdAt(now)
                .approvedBy(actor)
                .approvedAt(now)
                .build());
        Map<String, Object> detail = new HashMap<>();
        detail.put("tenantId", tenantId);
        detail.put("product", saved.getProduct().name());
        detail.put("discountPct", d);
        detail.put("commissionPct", c);
        detail.put("allowCommissionAboveEarned", saved.isAllowCommissionAboveEarned());
        detail.put("effectiveFrom", from.toString());
        detail.put("worstCaseNetPer10k", worst);
        detail.put("lossMaking", worst.signum() < 0);
        detail.put("createdBy", actor);
        auditService.record(PlatformAuditAction.PRICING_CREATE, ENTITY_TYPE, saved.getId(), detail);
        return toResponse(saved, isInForce(saved));
    }

    /** For rows created before rates were approved at once that are still pending; any platform admin may approve. */
    @Transactional
    public AgencyPricingResponse approve(String tenantId, String pricingId) {
        AgencyPricing row = repository.findByIdForUpdate(pricingId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Pricing not found: " + pricingId));
        if (row.getWithdrawnAt() != null) {
            throw new IllegalStateException("This rate was withdrawn");
        }
        if (row.getApprovedAt() != null) {
            throw new IllegalStateException("This rate is already approved");
        }
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        row.setApprovedBy(actor);
        row.setApprovedAt(Instant.now());
        AgencyPricing saved = repository.save(row);
        Map<String, Object> detail = new HashMap<>();
        detail.put("tenantId", tenantId);
        detail.put("product", saved.getProduct().name());
        detail.put("createdBy", saved.getCreatedBy());
        detail.put("worstCaseNetPer10k", saved.getWorstCaseNetPer10k());
        auditService.record(PlatformAuditAction.PRICING_APPROVE, ENTITY_TYPE, saved.getId(), detail);
        return toResponse(saved, isInForce(saved));
    }

    /** A rate that was never approved can be taken back; the row stays (nothing is deleted) and never applies. */
    @Transactional
    public AgencyPricingResponse withdraw(String tenantId, String pricingId) {
        AgencyPricing row = repository.findByIdForUpdate(pricingId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Pricing not found: " + pricingId));
        if (row.getApprovedAt() != null) {
            throw new IllegalStateException("An approved rate cannot be withdrawn");
        }
        if (row.getWithdrawnAt() != null) {
            throw new IllegalStateException("This rate is already withdrawn");
        }
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        row.setWithdrawnAt(Instant.now());
        row.setWithdrawnBy(actor);
        AgencyPricing saved = repository.save(row);
        Map<String, Object> detail = new HashMap<>();
        detail.put("tenantId", tenantId);
        detail.put("product", saved.getProduct().name());
        detail.put("createdBy", saved.getCreatedBy());
        auditService.record(PlatformAuditAction.PRICING_WITHDRAW, ENTITY_TYPE, saved.getId(), detail);
        return toResponse(saved, false);
    }

    /** Newest effectiveFrom first; {@code product} null means every product. */
    @Transactional(readOnly = true)
    public List<AgencyPricingResponse> history(String tenantId, PricingProduct product) {
        requireAgency(tenantId);
        List<AgencyPricing> rows = product == null
                ? repository.findByTenantIdOrderByEffectiveFromDesc(tenantId)
                : repository.findByTenantIdAndProductOrderByEffectiveFromDesc(tenantId, product);
        Instant now = Instant.now();
        Set<String> inForce = new HashSet<>();
        Set<PricingProduct> seen = EnumSet.noneOf(PricingProduct.class);
        for (AgencyPricing r : rows) {
            // rows are newest first, so the first applicable row per product is the resolve() row
            if (applies(r, now) && seen.add(r.getProduct())) {
                inForce.add(r.getId());
            }
        }
        return rows.stream().map(r -> toResponse(r, inForce.contains(r.getId()))).toList();
    }

    /**
     * The rate in force at {@code at}: among rows approved by {@code at} and effective by {@code at},
     * the latest effectiveFrom. A late approval never re-prices instants before it.
     */
    @Transactional(readOnly = true)
    public Optional<AgencyPricing> resolve(String tenantId, PricingProduct product, Instant at) {
        // shortcut: filters the agency's whole history in memory - a handful of rows per product; move to a query if rates churn
        return repository.findByTenantIdAndProductOrderByEffectiveFromDesc(tenantId, product).stream()
                .filter(r -> applies(r, at))
                .findFirst();
    }

    /** The rate in force now, or {@link PricingNotConfiguredException}: no row means Book is blocked, never a default rate. */
    @Transactional(readOnly = true)
    public AgencyPricing requireInForce(String tenantId, PricingProduct product) {
        return resolve(tenantId, product, Instant.now()).orElseThrow(PricingNotConfiguredException::new);
    }

    private static boolean applies(AgencyPricing r, Instant at) {
        return r.getWithdrawnAt() == null && r.getApprovedAt() != null && !r.getApprovedAt().isAfter(at) && !r.getEffectiveFrom().isAfter(at);
    }

    private boolean isInForce(AgencyPricing row) {
        return resolve(row.getTenantId(), row.getProduct(), Instant.now())
                .map(r -> r.getId().equals(row.getId())).orElse(false);
    }

    private void requireAgency(String tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new NotFoundException("Agency not found: " + tenantId);
        }
    }

    private static void requirePct(String name, BigDecimal pct) {
        if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(name + " % must be between 0 and 100");
        }
        if (pct.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(name + " % must have at most 2 decimals");
        }
    }

    private static AgencyPricingResponse toResponse(AgencyPricing p, boolean inForce) {
        return AgencyPricingResponse.builder()
                .id(p.getId())
                .tenantId(p.getTenantId())
                .product(p.getProduct())
                .discountPct(p.getDiscountPct())
                .commissionPct(p.getCommissionPct())
                .allowCommissionAboveEarned(p.isAllowCommissionAboveEarned())
                .effectiveFrom(p.getEffectiveFrom())
                .worstCaseNetPer10k(p.getWorstCaseNetPer10k())
                .requiresSecondApproval(p.isRequiresSecondApproval())
                .createdBy(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .approvedBy(p.getApprovedBy())
                .approvedAt(p.getApprovedAt())
                .withdrawnAt(p.getWithdrawnAt())
                .inForce(inForce)
                .build();
    }
}
