package com.voyra.crm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Platform-wide settings read by the pricing engine. Typed getters fall back to the seeded defaults. */
@Service
@RequiredArgsConstructor
public class PlatformSettingService {

    static final String DISCOUNT_BASE = "discount_base";
    static final String TDS_RATE = "tds_rate";
    static final String COMMISSION_ESTIMATE_PCT = "commission_estimate_pct";
    static final String REFERENCE_FARE_BASKET = "reference_fare_basket";

    private static final Map<String, String> DEFAULTS = Map.of(
            DISCOUNT_BASE, "GROSS",
            TDS_RATE, "0",
            COMMISSION_ESTIMATE_PCT, "5",
            REFERENCE_FARE_BASKET, "[{\"label\":\"Domestic LCC\",\"gross\":5000,\"base\":3500},"
                    + "{\"label\":\"Domestic FSC\",\"gross\":9000,\"base\":6000},"
                    + "{\"label\":\"International\",\"gross\":45000,\"base\":22000}]");

    private final PlatformSettingRepository repository;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<PlatformSettingResponse> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(PlatformSetting::getKey))
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DiscountBase discountBase() {
        return DiscountBase.valueOf(raw(DISCOUNT_BASE));
    }

    @Transactional(readOnly = true)
    public BigDecimal tdsRate() {
        return new BigDecimal(raw(TDS_RATE));
    }

    @Transactional(readOnly = true)
    public BigDecimal commissionEstimatePct() {
        return new BigDecimal(raw(COMMISSION_ESTIMATE_PCT));
    }

    @Transactional(readOnly = true)
    public List<ReferenceFare> referenceFareBasket() {
        return parseBasket(raw(REFERENCE_FARE_BASKET));
    }

    @Transactional
    public PlatformSettingResponse set(String key, String value) {
        if (!DEFAULTS.containsKey(key)) {
            throw new IllegalArgumentException("Unknown setting: " + key);
        }
        String v = value == null ? "" : value.trim();
        validate(key, v);
        String from = raw(key);
        PlatformSetting row = repository.findById(key).orElseGet(() -> PlatformSetting.builder().key(key).build());
        row.setValue(v);
        row.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        row.setUpdatedAt(Instant.now());
        PlatformSetting saved = repository.save(row);
        Map<String, Object> detail = new HashMap<>();
        detail.put("key", key);
        detail.put("from", from);
        detail.put("to", v);
        auditService.record(PlatformAuditAction.SETTING_SET, "PLATFORM_SETTING", key, detail);
        return toResponse(saved);
    }

    private String raw(String key) {
        return repository.findById(key).map(PlatformSetting::getValue).orElse(DEFAULTS.get(key));
    }

    private void validate(String key, String v) {
        switch (key) {
            case DISCOUNT_BASE -> {
                if (!v.equals("GROSS") && !v.equals("BASE")) {
                    throw new IllegalArgumentException(key + " must be GROSS or BASE");
                }
            }
            case TDS_RATE -> requireRange(key, v, 30);
            case COMMISSION_ESTIMATE_PCT -> requireRange(key, v, 100);
            default -> {
                List<ReferenceFare> basket;
                try {
                    basket = parseBasket(v);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(key + " must be a JSON list of {label, gross, base}");
                }
                if (basket.isEmpty() || basket.stream().anyMatch(f -> f.label() == null || f.gross() == null
                        || f.base() == null || f.base().signum() < 0 || f.base().compareTo(f.gross()) > 0)) {
                    throw new IllegalArgumentException(key + " needs at least one fare with 0 <= base <= gross");
                }
            }
        }
    }

    private static void requireRange(String key, String v, int max) {
        BigDecimal n;
        try {
            n = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number between 0 and " + max);
        }
        if (n.signum() < 0 || n.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException(key + " must be between 0 and " + max);
        }
    }

    private List<ReferenceFare> parseBasket(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("reference_fare_basket is not valid JSON", e);
        }
    }

    private PlatformSettingResponse toResponse(PlatformSetting s) {
        return PlatformSettingResponse.builder().key(s.getKey()).value(s.getValue()).updatedAt(s.getUpdatedAt()).build();
    }
}
