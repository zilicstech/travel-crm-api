package com.voyra.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PlanPricesRequest;
import com.voyra.crm.dto.PlanPricesResponse;
import com.voyra.crm.entity.PlatformSetting;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.models.PlanPrices;
import com.voyra.crm.repository.PlatformSettingRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Plans tab: reads and writes the Lite, Premium and extra-seat prices inside the sku_prices setting. */
@Service
@RequiredArgsConstructor
public class PlatformPlanService {

    private final PlatformSettingService settings;
    private final PlatformSettingRepository repository;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlanPricesResponse get() {
        return toResponse(settings.planPrices());
    }

    @Transactional
    public PlanPricesResponse set(PlanPricesRequest request) {
        PlanPricesResponse from = toResponse(settings.planPrices());
        PlanPricesResponse to = new PlanPricesResponse(scale(request.getLite()), scale(request.getSuite()), scale(request.getStaffSeats()));
        PlatformSetting row = repository.findById(PlatformSettingService.SKU_PRICES)
                .orElseGet(() -> PlatformSetting.builder().key(PlatformSettingService.SKU_PRICES).build());
        Map<String, Object> merged = parse(row.getValue());
        merged.put("LITE", to.getLite());
        merged.put("SUITE", to.getSuite());
        merged.put("STAFF_SEATS", to.getStaffSeats());
        try {
            row.setValue(objectMapper.writeValueAsString(merged));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sku_prices could not be written", e);
        }
        row.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        auditService.record(PlatformAuditAction.PLAN_PRICES_SET, "PLATFORM_SETTING", PlatformSettingService.SKU_PRICES,
                Map.of("from", audit(from), "to", audit(to)));
        return to;
    }

    private Map<String, Object> parse(String value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sku_prices is not valid JSON", e);
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static PlanPricesResponse toResponse(PlanPrices p) {
        return new PlanPricesResponse(p.lite(), p.suite(), p.staffSeats());
    }

    private static Map<String, String> audit(PlanPricesResponse p) {
        return Map.of("LITE", plain(p.getLite()), "SUITE", plain(p.getSuite()), "STAFF_SEATS", plain(p.getStaffSeats()));
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
