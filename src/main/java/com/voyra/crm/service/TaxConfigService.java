package com.voyra.crm.service;

import com.voyra.crm.dto.TaxRateConfigCreateRequest;
import com.voyra.crm.dto.TaxRateConfigResponse;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.repository.TaxRateConfigRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Owner/Accountant CRUD for GST and TCS slabs. A rate is never updated in place - {@link
 * #replace} closes the existing row ({@code effectiveTo}) and inserts a new one, so any
 * invoice already issued against the old row keeps meaning exactly what it did when it was
 * issued (the row itself is untouched; only its validity window closes).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxConfigService {

    private static final String[] AUDITED = {
            "label", "ratePercent", "taxablePercent", "thresholdAmount", "isDefault", "isActive"
    };

    private final TaxRateConfigRepository taxRateConfigRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<TaxRateConfigResponse> list(TaxKind kindFilter) {
        List<TaxRateConfig> rows = kindFilter != null
                ? taxRateConfigRepository.findByTaxKindOrderBySupplyNatureAscEffectiveFromDesc(kindFilter)
                : taxRateConfigRepository.findAllByOrderByTaxKindAscSupplyNatureAscEffectiveFromDesc();
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public TaxRateConfigResponse create(TaxRateConfigCreateRequest request) {
        TaxRateConfig config = fromRequest(request);
        taxRateConfigRepository.save(config);
        auditService.recordCreate(AuditEntityType.TAX_RATE_CONFIG, config.getId(), config.getLabel());
        log.info("Tax rate config created: id={}, kind={}, supplyNature={}", config.getId(), config.getTaxKind(), config.getSupplyNature());
        return toResponse(config);
    }

    /** Closes the existing row and inserts the replacement as a new version. */
    @Transactional
    public TaxRateConfigResponse replace(String id, TaxRateConfigCreateRequest request) {
        TaxRateConfig existing = findById(id);
        Map<String, String> before = AuditSnapshot.of(existing, AUDITED);

        // Never let the closing date fall before the row's own start - a same-day replace
        // (new effectiveFrom == existing effectiveFrom) would otherwise produce an inverted
        // window. isActive=false already excludes it from TaxEngine lookups either way; this
        // keeps the audit trail honest for whoever reads it later.
        LocalDate effectiveTo = request.getEffectiveFrom().minusDays(1);
        if (effectiveTo.isBefore(existing.getEffectiveFrom())) {
            effectiveTo = existing.getEffectiveFrom();
        }
        existing.setEffectiveTo(effectiveTo);
        existing.setIsActive(false);
        existing.setUpdatedAt(LocalDateTime.now());
        existing.setUpdatedBy(currentUserId());
        taxRateConfigRepository.save(existing);
        auditService.recordUpdate(AuditEntityType.TAX_RATE_CONFIG, existing.getId(), existing.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(existing, AUDITED)));

        TaxRateConfig replacement = fromRequest(request);
        taxRateConfigRepository.save(replacement);
        auditService.recordCreate(AuditEntityType.TAX_RATE_CONFIG, replacement.getId(),
                replacement.getLabel() + " (replaces " + existing.getId() + ")");

        log.info("Tax rate config replaced: oldId={}, newId={}", existing.getId(), replacement.getId());
        return toResponse(replacement);
    }

    @Transactional
    public TaxRateConfigResponse updateStatus(String id, boolean isActive) {
        TaxRateConfig config = findById(id);
        Map<String, String> before = AuditSnapshot.of(config, AUDITED);

        config.setIsActive(isActive);
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(currentUserId());
        taxRateConfigRepository.save(config);

        auditService.recordUpdate(AuditEntityType.TAX_RATE_CONFIG, config.getId(), config.getLabel(),
                AuditSnapshot.diff(before, AuditSnapshot.of(config, AUDITED)));
        log.info("Tax rate config status updated: id={}, isActive={}", id, isActive);
        return toResponse(config);
    }

    private TaxRateConfig fromRequest(TaxRateConfigCreateRequest request) {
        return TaxRateConfig.builder()
                .id(UniqueIdResolver.resolve(taxRateConfigRepository::existsById))
                .taxKind(request.getTaxKind())
                .label(request.getLabel())
                .sacCode(request.getSacCode())
                .supplyNature(request.getSupplyNature())
                .ratePercent(request.getRatePercent())
                .taxablePercent(request.getTaxablePercent() != null ? request.getTaxablePercent() : new BigDecimal("100.000"))
                .thresholdAmount(request.getThresholdAmount())
                .tcsSection(request.getTcsSection())
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
                .isDefault(Boolean.TRUE.equals(request.getIsDefault()))
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
    }

    private TaxRateConfig findById(String id) {
        return taxRateConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tax rate config not found: " + id));
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private TaxRateConfigResponse toResponse(TaxRateConfig c) {
        return TaxRateConfigResponse.builder()
                .id(c.getId()).taxKind(c.getTaxKind()).label(c.getLabel()).sacCode(c.getSacCode())
                .supplyNature(c.getSupplyNature()).ratePercent(c.getRatePercent())
                .taxablePercent(c.getTaxablePercent()).thresholdAmount(c.getThresholdAmount())
                .tcsSection(c.getTcsSection()).effectiveFrom(c.getEffectiveFrom()).effectiveTo(c.getEffectiveTo())
                .isDefault(c.getIsDefault()).isActive(c.getIsActive())
                .build();
    }
}
