package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingAssignRequest;
import com.voyra.crm.dto.AgencyPricingAssignmentResponse;
import com.voyra.crm.dto.PricingRuleCreateRequest;
import com.voyra.crm.dto.PricingRuleResponse;
import com.voyra.crm.dto.PricingRuleUpdateRequest;
import com.voyra.crm.entity.AgencyPricingAssignment;
import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.repository.AgencyPricingAssignmentRepository;
import com.voyra.crm.repository.PricingRuleRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared pricing rules (design 2026-10-27-pricing-rules-design.md): named discount and commission percentages per
 * product, the agencies' assignment to them (no assignment = the Default rules), and the pair a booking is priced
 * with. Any platform admin creates, edits and assigns;
 */
@Service
@RequiredArgsConstructor
public class AgencyPricingService {

    private static final String RULE_ENTITY = "PRICING_RULE";
    private static final String ASSIGN_ENTITY = "AGENCY_PRICING";

    private final PricingRuleRepository ruleRepository;
    private final AgencyPricingAssignmentRepository assignmentRepository;
    private final TenantRepository tenantRepository;
    private final PlatformAuditService auditService;

    /** The discount and commission rule a booking is priced with. {@link #pricingId()} changes on any edit or reassignment. */
    public record EffectivePricing(PricingRule discount, PricingRule commission) {
        public String pricingId() {
            return "D:" + discount.getId() + ":v" + discount.getVersion() + "|C:" + commission.getId() + ":v" + commission.getVersion();
        }
    }

    // ---- rules

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listRules(PricingProduct product) {
        List<PricingRule> rules = product == null ? ruleRepository.findAll() : ruleRepository.findByProduct(product);
        Usage usage = usage();
        return rules.stream()
                .sorted(Comparator.comparing(PricingRule::getProduct).thenComparing(PricingRule::getKind)
                        .thenComparing(r -> !r.isDefault()).thenComparing(PricingRule::getPct).thenComparing(PricingRule::getName))
                .map(r -> toResponse(r, usage.count(r))).toList();
    }

    @Transactional
    public PricingRuleResponse createRule(PricingRuleCreateRequest request) {
        String name = request.getName().trim();
        if (ruleRepository.existsByKindAndProductAndNameIgnoreCase(request.getKind(), request.getProduct(), name)) {
            throw new IllegalStateException("A " + label(request.getKind(), request.getProduct()) + " rule named \"" + name + "\" already exists");
        }
        CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
        Instant now = Instant.now();
        PricingRule saved = ruleRepository.save(PricingRule.builder()
                .id(UniqueIdResolver.resolve(ruleRepository::existsById))
                .kind(request.getKind()).product(request.getProduct()).name(name)
                .pct(scaled(request.getPct()))
                .isDefault(false).version(1)
                .createdBy(actor.userId()).createdAt(now)
                .updatedBy(actor.userId()).updatedByName(actor.username()).updatedAt(now)
                .build());
        auditService.record(PlatformAuditAction.PRICING_RULE_CREATE, RULE_ENTITY, saved.getId(), ruleDetail(saved));
        return toResponse(saved, 0);
    }

    /** Applies at once to every agency on the rule (from their next review); the audit row says how many. */
    @Transactional
    public PricingRuleResponse updateRule(String ruleId, PricingRuleUpdateRequest request) {
        PricingRule rule = ruleRepository.findByIdForUpdate(ruleId).orElseThrow(() -> new NotFoundException("Pricing rule not found"));
        String name = request.getName().trim();
        if (ruleRepository.existsByKindAndProductAndNameIgnoreCaseAndIdNot(rule.getKind(), rule.getProduct(), name, ruleId)) {
            throw new IllegalStateException("A " + label(rule.getKind(), rule.getProduct()) + " rule named \"" + name + "\" already exists");
        }
        BigDecimal pct = scaled(request.getPct());
        Map<String, Object> detail = new HashMap<>();
        detail.put("kind", rule.getKind().name());
        detail.put("product", rule.getProduct().name());
        detail.put("nameBefore", rule.getName());
        detail.put("pctBefore", rule.getPct());
        boolean valueChanged = pct.compareTo(rule.getPct()) != 0;
        CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
        rule.setName(name);
        rule.setPct(pct);
        if (valueChanged) {
            rule.setVersion(rule.getVersion() + 1);
        }
        rule.setUpdatedBy(actor.userId());
        rule.setUpdatedByName(actor.username());
        rule.setUpdatedAt(Instant.now());
        PricingRule saved = ruleRepository.save(rule);
        detail.put("name", name);
        detail.put("pct", pct);
        long agencyCount = usage().count(saved);
        detail.put("agencyCount", agencyCount);
        auditService.record(PlatformAuditAction.PRICING_RULE_UPDATE, RULE_ENTITY, ruleId, detail);
        return toResponse(saved, agencyCount);
    }

    /** Only an unused, non-default rule; the foreign keys refuse it too if an assignment races in. */
    @Transactional
    public void deleteRule(String ruleId) {
        PricingRule rule = ruleRepository.findByIdForUpdate(ruleId).orElseThrow(() -> new NotFoundException("Pricing rule not found"));
        if (rule.isDefault()) {
            throw new BookingConflictException("DEFAULT_RULE", "The Default rule cannot be deleted", Map.of());
        }
        int used = assignmentRepository.findUsing(ruleId).size();
        if (used > 0) {
            throw new BookingConflictException("RULE_IN_USE", "Used by " + used + (used == 1 ? " agency" : " agencies")
                    + ". Move them to another rule first.", Map.of("agencyCount", used));
        }
        ruleRepository.delete(rule);
        auditService.record(PlatformAuditAction.PRICING_RULE_DELETE, RULE_ENTITY, ruleId, ruleDetail(rule));
    }

    // ---- assignment

    @Transactional(readOnly = true)
    public List<AgencyPricingAssignmentResponse> assignments(String tenantId) {
        requireAgency(tenantId);
        List<AgencyPricingAssignmentResponse> out = new ArrayList<>();
        for (PricingProduct product : PricingProduct.values()) {
            out.add(toAssignmentResponse(tenantId, product, assignmentRepository.findByTenantIdAndProduct(tenantId, product).orElse(null)));
        }
        return out;
    }

    @Transactional
    public AgencyPricingAssignmentResponse assign(String tenantId, PricingProduct product, AgencyPricingAssignRequest request) {
        requireAgency(tenantId);
        PricingRule discount = ruleOf(request.getDiscountRuleId(), PricingRuleKind.DISCOUNT, product);
        PricingRule commission = ruleOf(request.getCommissionRuleId(), PricingRuleKind.COMMISSION, product);
        CustomUserPrincipal actor = SecurityContextUtil.getCurrentUserOrThrow();
        AgencyPricingAssignment row = assignmentRepository.findByTenantIdAndProduct(tenantId, product)
                .orElseGet(() -> AgencyPricingAssignment.builder().tenantId(tenantId).product(product).build());
        Map<String, Object> detail = new HashMap<>();
        detail.put("tenantId", tenantId);
        detail.put("product", product.name());
        detail.put("discountRuleBefore", row.getDiscountRuleId());
        detail.put("commissionRuleBefore", row.getCommissionRuleId());
        row.setDiscountRuleId(discount.getId());
        row.setCommissionRuleId(commission.getId());
        row.setUpdatedBy(actor.userId());
        row.setUpdatedByName(actor.username());
        row.setUpdatedAt(Instant.now());
        assignmentRepository.save(row);
        detail.put("discountRule", discount.getName());
        detail.put("discountPct", discount.getPct());
        detail.put("commissionRule", commission.getName());
        detail.put("commissionPct", commission.getPct());
        auditService.record(PlatformAuditAction.PRICING_ASSIGN, ASSIGN_ENTITY, tenantId, detail);
        return toAssignmentResponse(tenantId, product, row);
    }

    // ---- booking time

    /** The agency's rules for a product: its assignment, else the Default rules. Empty only if a Default rule is missing. */
    @Transactional(readOnly = true)
    public Optional<EffectivePricing> resolve(String tenantId, PricingProduct product) {
        Optional<AgencyPricingAssignment> row = assignmentRepository.findByTenantIdAndProduct(tenantId, product);
        Optional<PricingRule> discount = row.map(a -> ruleRepository.findById(a.getDiscountRuleId()))
                .orElseGet(() -> ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, product));
        Optional<PricingRule> commission = row.map(a -> ruleRepository.findById(a.getCommissionRuleId()))
                .orElseGet(() -> ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, product));
        return discount.flatMap(d -> commission.map(c -> new EffectivePricing(d, c)));
    }

    @Transactional(readOnly = true)
    public EffectivePricing requireInForce(String tenantId, PricingProduct product) {
        return resolve(tenantId, product).orElseThrow(PricingNotConfiguredException::new);
    }

    // ---- helpers

    /** Who is on which rule, read once: explicit assignments, plus every agency without one is on the Default rules. */
    private record Usage(Map<String, Long> explicit, Map<PricingProduct, Long> unassigned) {
        long count(PricingRule r) {
            return explicit.getOrDefault(r.getId(), 0L) + (r.isDefault() ? unassigned.getOrDefault(r.getProduct(), 0L) : 0L);
        }
    }

    private Usage usage() {
        List<AgencyPricingAssignment> all = assignmentRepository.findAll();
        Map<String, Long> explicit = new HashMap<>();
        for (AgencyPricingAssignment a : all) {
            explicit.merge(a.getDiscountRuleId(), 1L, Long::sum);
            explicit.merge(a.getCommissionRuleId(), 1L, Long::sum);
        }
        long agencies = tenantRepository.count();
        Map<PricingProduct, Long> unassigned = new HashMap<>();
        for (PricingProduct p : PricingProduct.values()) {
            unassigned.put(p, agencies - all.stream().filter(a -> a.getProduct() == p).count());
        }
        return new Usage(explicit, unassigned);
    }

    private AgencyPricingAssignmentResponse toAssignmentResponse(String tenantId, PricingProduct product, AgencyPricingAssignment row) {
        EffectivePricing p = resolve(tenantId, product).orElseThrow(PricingNotConfiguredException::new);
        Usage usage = usage();
        return AgencyPricingAssignmentResponse.builder()
                .product(product)
                .discountRule(toResponse(p.discount(), usage.count(p.discount())))
                .commissionRule(toResponse(p.commission(), usage.count(p.commission())))
                .assigned(row != null)
                .updatedAt(row == null ? null : row.getUpdatedAt())
                .updatedByName(row == null ? null : row.getUpdatedByName())
                .build();
    }

    private PricingRule ruleOf(String id, PricingRuleKind kind, PricingProduct product) {
        PricingRule r = ruleRepository.findById(id).orElseThrow(() -> new NotFoundException("Pricing rule not found: " + id));
        if (r.getKind() != kind || r.getProduct() != product) {
            throw new IllegalArgumentException("Choose a " + label(kind, product) + " rule");
        }
        return r;
    }

    private void requireAgency(String tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new NotFoundException("Agency not found: " + tenantId);
        }
    }

    private static BigDecimal scaled(BigDecimal pct) {
        return pct.setScale(2, RoundingMode.HALF_UP);
    }

    private static String label(PricingRuleKind kind, PricingProduct product) {
        return (product == PricingProduct.FLIGHT ? "flight " : "hotel ") + (kind == PricingRuleKind.DISCOUNT ? "discount" : "commission");
    }

    private static Map<String, Object> ruleDetail(PricingRule r) {
        Map<String, Object> d = new HashMap<>();
        d.put("kind", r.getKind().name());
        d.put("product", r.getProduct().name());
        d.put("name", r.getName());
        d.put("pct", r.getPct());
        return d;
    }

    private static PricingRuleResponse toResponse(PricingRule r, long agencyCount) {
        return PricingRuleResponse.builder()
                .id(r.getId()).kind(r.getKind()).product(r.getProduct()).name(r.getName()).pct(r.getPct())
                .isDefault(r.isDefault()).version(r.getVersion())
                .agencyCount(agencyCount).updatedAt(r.getUpdatedAt()).updatedByName(r.getUpdatedByName())
                .build();
    }
}
