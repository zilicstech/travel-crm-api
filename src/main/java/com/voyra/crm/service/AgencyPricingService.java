package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingCreateRequest;
import com.voyra.crm.dto.AgencyPricingResponse;
import com.voyra.crm.dto.PricingPreviewResponse;
import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
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
 * Per-agency discount and commission rates. A worst-case margin guard over the reference fare
 * basket decides whether a second platform admin must approve a rate before it can take effect.
 */
@Service
@RequiredArgsConstructor
public class AgencyPricingService {

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
        List<ReferenceFare> basket = settings.referenceFareBasket();
        BigDecimal worst = PricingMath.worstCaseNetPer10k(basket, discountPct, commissionPct, earned, db, allowCommissionAboveEarned);
        List<PricingPreviewResponse.BasketLine> lines = basket.stream().map(f -> PricingPreviewResponse.BasketLine.builder()
                .label(f.label())
                .displayed(PricingMath.displayed(f.gross(), f.base(), discountPct, db))
                .voyraNet(PricingMath.voyraNet(f.gross(), f.base(), discountPct, commissionPct, earned, db, allowCommissionAboveEarned))
                .build()).toList();
        return PricingPreviewResponse.builder()
                .worstCaseNetPer10k(worst)
                .requiresSecondApproval(needsSecondApproval(worst, allowCommissionAboveEarned))
                .basketLines(lines)
                .build();
    }

    @Transactional
    public AgencyPricingResponse create(String tenantId, AgencyPricingCreateRequest request) {
        requireAgency(tenantId);
        Instant now = Instant.now();
        Instant from = request.getEffectiveFrom();
        // one minute of slack for clock skew between the browser and the server
        if (from.isBefore(now.minus(1, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Effective date must be now or later");
        }
        if (repository.existsByTenantIdAndProductAndEffectiveFrom(tenantId, request.getProduct(), from)) {
            throw new IllegalStateException("A " + request.getProduct() + " rate already starts at " + from + " for this agency");
        }
        // stored as NUMERIC(5,2): guard on the value that will actually be saved
        BigDecimal d = request.getDiscountPct().setScale(2, RoundingMode.HALF_UP);
        BigDecimal c = request.getCommissionPct().setScale(2, RoundingMode.HALF_UP);
        BigDecimal worst = PricingMath.worstCaseNetPer10k(settings.referenceFareBasket(), d, c,
                settings.commissionEstimatePct(), settings.discountBase(), request.isAllowCommissionAboveEarned());
        boolean second = needsSecondApproval(worst, request.isAllowCommissionAboveEarned());
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
                .requiresSecondApproval(second)
                .createdBy(actor)
                .createdAt(now)
                .approvedBy(second ? null : actor)
                .approvedAt(second ? null : now)
                .build());
        Map<String, Object> detail = new HashMap<>();
        detail.put("tenantId", tenantId);
        detail.put("product", saved.getProduct().name());
        detail.put("discountPct", d);
        detail.put("commissionPct", c);
        detail.put("allowCommissionAboveEarned", saved.isAllowCommissionAboveEarned());
        detail.put("effectiveFrom", from.toString());
        detail.put("worstCaseNetPer10k", worst);
        detail.put("requiresSecondApproval", second);
        auditService.record(PlatformAuditAction.PRICING_CREATE, ENTITY_TYPE, saved.getId(), detail);
        return toResponse(saved, isInForce(saved));
    }

    @Transactional
    public AgencyPricingResponse approve(String tenantId, String pricingId) {
        AgencyPricing row = repository.findById(pricingId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Pricing not found: " + pricingId));
        if (row.getApprovedAt() != null) {
            throw new IllegalStateException("This rate is already approved");
        }
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        if (actor.equals(row.getCreatedBy())) {
            throw new IllegalStateException("A different platform admin must approve a loss-making rate");
        }
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

    /** The rate in force at {@code at}: the latest approved row with effectiveFrom at or before it. */
    @Transactional(readOnly = true)
    public Optional<AgencyPricing> resolve(String tenantId, PricingProduct product, Instant at) {
        // shortcut: filters the agency's whole history in memory - a handful of rows per product; move to a query if rates churn
        return repository.findByTenantIdAndProductOrderByEffectiveFromDesc(tenantId, product).stream()
                .filter(r -> applies(r, at))
                .findFirst();
    }

    private static boolean applies(AgencyPricing r, Instant at) {
        return r.getApprovedAt() != null && !r.getEffectiveFrom().isAfter(at);
    }

    private boolean isInForce(AgencyPricing row) {
        return resolve(row.getTenantId(), row.getProduct(), Instant.now())
                .map(r -> r.getId().equals(row.getId())).orElse(false);
    }

    private static boolean needsSecondApproval(BigDecimal worstCaseNetPer10k, boolean allowCommissionAboveEarned) {
        return worstCaseNetPer10k.signum() < 0 || allowCommissionAboveEarned;
    }

    private void requireAgency(String tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new IllegalArgumentException("Agency not found: " + tenantId);
        }
    }

    private static void requirePct(String name, BigDecimal pct) {
        if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(name + " % must be between 0 and 100");
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
                .inForce(inForce)
                .build();
    }
}
