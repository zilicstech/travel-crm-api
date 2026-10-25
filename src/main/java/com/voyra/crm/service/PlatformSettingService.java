package com.voyra.crm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PlatformSettingResponse;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.ValidationPatterns;
import com.voyra.crm.models.VoyraLegal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingService {

    static final String DISCOUNT_BASE = "discount_base";
    static final String TDS_RATE = "tds_rate";
    static final String COMMISSION_ESTIMATE_PCT = "commission_estimate_pct";
    static final String VOYRA_CANCEL_FEE = "voyra_cancel_fee";
    static final String REFERENCE_FARE_BASKET = "reference_fare_basket";
    static final String REFERENCE_FARE_BASKET_HOTEL = "reference_fare_basket_hotel";
    /** Off until a second platform admin approves it ({@link PlatformSettingRequestService}); guards the Tripjack live switch. */
    static final String TRIPJACK_LIVE_READY = "tripjack_live_ready";
    static final String PAX_RETENTION_DAYS = "pax_retention_days";
    static final String SKU_PRICES = "sku_prices";
    static final String FOUNDING_AGENCIES = "founding_agencies";
    static final String VOYRA_LEGAL_NAME = "voyra_legal_name";
    static final String VOYRA_GSTIN = "voyra_gstin";
    static final String VOYRA_ADDRESS = "voyra_address";
    static final String VOYRA_STATE_CODE = "voyra_state_code";
    static final String SUBSCRIPTION_SAC = "subscription_sac";
    /** Monthly INR, excl. GST: one per SKU (STAFF_SEATS per seat) plus SUITE, which includes 5 seats. */
    private static final Set<String> SKU_PRICE_KEYS = Set.of("CRM_CORE", "STAFF_SEATS", "ACCOUNTS_GST", "REPORTS", "VISA", "SUITE");

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(DISCOUNT_BASE, "GROSS"),
            Map.entry(TDS_RATE, "0"),
            Map.entry(COMMISSION_ESTIMATE_PCT, "5"),
            Map.entry(VOYRA_CANCEL_FEE, "0"),
            Map.entry(SKU_PRICES, "{\"CRM_CORE\":2499,\"STAFF_SEATS\":399,\"ACCOUNTS_GST\":1999,\"REPORTS\":699,\"VISA\":499,\"SUITE\":4999}"),
            Map.entry(FOUNDING_AGENCIES, "[\"Voyra Global\"]"),
            Map.entry(REFERENCE_FARE_BASKET, "[{\"label\":\"Domestic LCC\",\"gross\":5000,\"base\":3500},"
                    + "{\"label\":\"Domestic FSC\",\"gross\":9000,\"base\":6000},"
                    + "{\"label\":\"International\",\"gross\":45000,\"base\":22000}]"),
            Map.entry(REFERENCE_FARE_BASKET_HOTEL, "[{\"label\":\"Budget 3*\",\"gross\":3150,\"base\":3000},"
                    + "{\"label\":\"Mid 4*\",\"gross\":9440,\"base\":8000},"
                    + "{\"label\":\"Premium 5*\",\"gross\":23600,\"base\":20000}]"),
            Map.entry(PAX_RETENTION_DAYS, "90"),
            Map.entry(TRIPJACK_LIVE_READY, "false"),
            Map.entry(VOYRA_LEGAL_NAME, ""),
            Map.entry(VOYRA_GSTIN, ""),
            Map.entry(VOYRA_ADDRESS, ""),
            Map.entry(VOYRA_STATE_CODE, ""),
            Map.entry(SUBSCRIPTION_SAC, "998314"));

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

    /** The sample fares the pricing guard tests a rate against: hotel rooms for HOTEL, flights otherwise. */
    @Transactional(readOnly = true)
    public List<ReferenceFare> referenceFareBasket(PricingProduct product) {
        return parseBasket(raw(product == PricingProduct.HOTEL ? REFERENCE_FARE_BASKET_HOTEL : REFERENCE_FARE_BASKET));
    }

    /** Days a finished booking keeps its passenger data before the DPDP purge removes it. */
    @Transactional(readOnly = true)
    public int paxRetentionDays() {
        return Integer.parseInt(raw(PAX_RETENTION_DAYS));
    }

    /** Whether a second platform admin has cleared Tripjack for live (PROD credential, Tripjack booking providers). */
    @Transactional(readOnly = true)
    public boolean tripjackLiveReady() {
        return "true".equals(raw(TRIPJACK_LIVE_READY));
    }

    /** Only for {@link PlatformSettingRequestService}: writes a change a second admin just approved, in the approval's transaction, audited like {@link #set}. */
    @Transactional
    public void applyApproved(String key, String value) {
        write(key, value);
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

    /** Voyra's identity on subscription tax invoices; blanks are null and the state code falls back to the GSTIN. */
    @Transactional(readOnly = true)
    public VoyraLegal voyraLegal() {
        String gstin = blankToNull(raw(VOYRA_GSTIN));
        gstin = gstin == null ? null : gstin.toUpperCase(java.util.Locale.ROOT);
        String state = blankToNull(raw(VOYRA_STATE_CODE));
        return new VoyraLegal(blankToNull(raw(VOYRA_LEGAL_NAME)), gstin, blankToNull(raw(VOYRA_ADDRESS)),
                state != null ? state : gstin != null ? gstin.substring(0, 2) : null);
    }

    /** SAC printed on subscription invoices (998314: IT design and development / SaaS). */
    @Transactional(readOnly = true)
    public String subscriptionSac() {
        return raw(SUBSCRIPTION_SAC);
    }

    /** Subscription tax invoices need Voyra's GSTIN; empty is allowed locally, so say so loudly. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnIfVoyraGstinMissing() {
        try {
            if (blankToNull(raw(VOYRA_GSTIN)) == null) {
                log.warn("Platform setting voyra_gstin is empty - subscription invoices print without Voyra's GSTIN. Set it in Platform settings.");
            }
        } catch (RuntimeException e) {
            log.warn("Could not read platform setting voyra_gstin at startup: {}", e.getMessage());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
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
        return write(key, v);
    }

    private PlatformSettingResponse write(String key, String v) {
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
            case PAX_RETENTION_DAYS -> {
                if (!v.matches("[0-9]{1,4}") || Integer.parseInt(v) < 1) {
                    throw new IllegalArgumentException(key + " must be a whole number of days, 1 or more");
                }
            }
            case SKU_PRICES -> {
                Map<String, BigDecimal> prices = readJson(key, v, new TypeReference<>() { });
                if (prices == null || !prices.keySet().equals(SKU_PRICE_KEYS) || prices.values().stream()
                        .anyMatch(p -> p == null || p.signum() < 0 || p.compareTo(BigDecimal.valueOf(1_000_000)) > 0)) {
                    throw new IllegalArgumentException(key + " needs exactly " + SKU_PRICE_KEYS.stream().sorted().toList()
                            + ", each a monthly INR price between 0 and 1000000");
                }
            }
            case VOYRA_LEGAL_NAME -> requireLength(key, v, 200);
            case VOYRA_ADDRESS -> requireLength(key, v, 500);
            case VOYRA_GSTIN -> {
                if (!v.matches(ValidationPatterns.GSTIN)) {
                    throw new IllegalArgumentException(key + " must be a 15-character GSTIN (e.g. 27AAACV1234F1Z5) or empty");
                }
                ValidationPatterns.requireGstinMatchesState(v, raw(VOYRA_STATE_CODE));
            }
            case VOYRA_STATE_CODE -> {
                if (!v.isEmpty() && !v.matches("[0-9]{2}")) {
                    throw new IllegalArgumentException(key + " must be a 2-digit GST state code (e.g. 27) or empty");
                }
                ValidationPatterns.requireGstinMatchesState(raw(VOYRA_GSTIN), v);
            }
            case SUBSCRIPTION_SAC -> {
                if (!v.matches("[0-9]{4,8}")) {
                    throw new IllegalArgumentException(key + " must be a 4-8 digit SAC code");
                }
            }
            case TRIPJACK_LIVE_READY -> {
                if (v.equals("true")) {
                    throw new IllegalArgumentException(key + " is turned on by a second platform admin: request the change, and another admin approves it");
                }
                if (!v.equals("false")) {
                    throw new IllegalArgumentException(key + " must be true or false");
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

    private static void requireLength(String key, String v, int max) {
        if (v.length() > max) {
            throw new IllegalArgumentException(key + " must be at most " + max + " characters");
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
            throw new IllegalArgumentException("The reference fare basket is not valid JSON", e);
        }
    }

    private PlatformSettingResponse toResponse(PlatformSetting s) {
        return PlatformSettingResponse.builder().key(s.getKey()).value(s.getValue()).updatedAt(s.getUpdatedAt()).build();
    }
}
