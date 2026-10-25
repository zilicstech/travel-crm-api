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
import java.util.Set;

/** Platform-wide settings read by the pricing engine. Typed getters fall back to the seeded defaults. */
@Service
@RequiredArgsConstructor
public class PlatformSettingService {

    static final String DISCOUNT_BASE = "discount_base";
    static final String TDS_RATE = "tds_rate";
    static final String COMMISSION_ESTIMATE_PCT = "commission_estimate_pct";
    static final String VOYRA_CANCEL_FEE = "voyra_cancel_fee";
    static final String REFERENCE_FARE_BASKET = "reference_fare_basket";
    static final String SKU_PRICES = "sku_prices";
    static final String FOUNDING_AGENCIES = "founding_agencies";
    /** Monthly INR, excl. GST: one per SKU (STAFF_SEATS per seat) plus SUITE, which includes 5 seats. */
    private static final Set<String> SKU_PRICE_KEYS = Set.of("CRM_CORE", "STAFF_SEATS", "ACCOUNTS_GST", "REPORTS", "VISA", "SUITE");

    private static final Map<String, String> DEFAULTS = Map.of(
            DISCOUNT_BASE, "GROSS",
            TDS_RATE, "0",
            COMMISSION_ESTIMATE_PCT, "5",
            VOYRA_CANCEL_FEE, "0",
            SKU_PRICES, "{\"CRM_CORE\":2499,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999}",
            FOUNDING_AGENCIES, "[\"Voyra Global\"]",
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

    /** INR charged by Voyra on a cancellation, on top of the supplier's own fee. */
    @Transactional(readOnly = true)
    public BigDecimal voyraCancelFee() {
        return new BigDecimal(raw(VOYRA_CANCEL_FEE));
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

    /** Monthly INR price per key of {@link #SKU_PRICE_KEYS}; annual billing is 10 of these. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> skuPrices() {
        return readJson(SKU_PRICES, raw(SKU_PRICES), new TypeReference<>() { });
    }

    /** Agency names (matched to tenant.agency_name, ignoring case) that never pay for Suite. */
    @Transactional(readOnly = true)
    public List<String> foundingAgencies() {
        return readJson(FOUNDING_AGENCIES, raw(FOUNDING_AGENCIES), new TypeReference<>() { });
    }

    @Transactional
    public PlatformSettingResponse set(String key, String value) {
        if (!DEFAULTS.containsKey(key)) {
            throw new IllegalArgumentException("Unknown setting: " + key);
        }
        String v = value == null ? "" : value.trim();
        if (v.length() > 4000) {
            throw new IllegalArgumentException(key + " must be at most 4000 characters");
        }
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
            case VOYRA_CANCEL_FEE -> requireRange(key, v, 1_000_000);
            case SKU_PRICES -> {
                Map<String, BigDecimal> prices = readJson(key, v, new TypeReference<>() { });
                if (prices == null || !prices.keySet().equals(SKU_PRICE_KEYS) || prices.values().stream()
                        .anyMatch(p -> p == null || p.signum() < 0 || p.compareTo(BigDecimal.valueOf(1_000_000)) > 0)) {
                    throw new IllegalArgumentException(key + " needs exactly " + SKU_PRICE_KEYS.stream().sorted().toList()
                            + ", each a monthly INR price between 0 and 1000000");
                }
            }
            case FOUNDING_AGENCIES -> {
                List<String> names = readJson(key, v, new TypeReference<>() { });
                if (names == null || names.stream().anyMatch(n -> n == null || n.isBlank() || n.length() > 150)) {
                    throw new IllegalArgumentException(key + " must be a JSON list of agency names (1-150 characters each)");
                }
            }
            default -> {
                List<ReferenceFare> basket;
                try {
                    basket = parseBasket(v);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(key + " must be a JSON list of {label, gross, base}");
                }
                if (basket == null || basket.isEmpty() || basket.stream().anyMatch(f -> f == null
                        || f.label() == null || f.label().isBlank() || f.gross() == null || f.gross().signum() <= 0 || f.base() == null || f.base().signum() < 0 || f.base().compareTo(f.gross()) > 0)) {
                    throw new IllegalArgumentException(key + " needs at least one fare, each with a label and 0 <= base <= gross, gross above 0");
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

    private <T> T readJson(String key, String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " is not valid JSON of the expected shape", e);
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
