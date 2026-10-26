package com.voyra.crm.service;

import com.voyra.crm.dto.MarkupDefaultResponse;
import com.voyra.crm.dto.MarkupDefaultUpdateRequest;
import com.voyra.crm.entity.MarkupDefault;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.MarkupDefaultRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Owner-set defaults an agent's quote line pre-fills from - see {@code entity.MarkupDefault}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkupDefaultService {

    // above 100 percent is legitimate (resale at a multiple); the flat cap keeps well inside NUMERIC(19,4)
    private static final BigDecimal MAX_PERCENT = new BigDecimal("1000");
    private static final BigDecimal MAX_FLAT = new BigDecimal("99999999");

    private final MarkupDefaultRepository markupDefaultRepository;
    private final AuditService auditService;

    /** Every ServiceType, always - a type with no row yet reads as 0% so the frontend never
     *  has to handle a missing entry. */
    @Transactional(readOnly = true)
    public List<MarkupDefaultResponse> list() {
        Map<ServiceType, MarkupDefault> existing = markupDefaultRepository.findAll().stream()
                .collect(Collectors.toMap(MarkupDefault::getServiceType, d -> d));
        return Arrays.stream(ServiceType.values())
                .map(type -> {
                    MarkupDefault d = existing.get(type);
                    return MarkupDefaultResponse.builder()
                            .serviceType(type)
                            .mode(d != null ? d.getMode() : MarkupMode.PERCENT)
                            .value(d != null ? d.getValue() : BigDecimal.ZERO)
                            .build();
                })
                .toList();
    }

    @Transactional
    public MarkupDefaultResponse upsert(ServiceType serviceType, MarkupDefaultUpdateRequest request) {
        if (request.getMode() == MarkupMode.PERCENT && request.getValue().compareTo(MAX_PERCENT) > 0) {
            throw new IllegalArgumentException("Percent markup cannot be more than 1000");
        }
        if (request.getMode() == MarkupMode.FLAT && request.getValue().compareTo(MAX_FLAT) > 0) {
            throw new IllegalArgumentException("Flat markup cannot be more than 99,999,999");
        }
        MarkupDefault d = markupDefaultRepository.findById(serviceType)
                .orElseGet(() -> MarkupDefault.builder().serviceType(serviceType).build());
        d.setMode(request.getMode());
        d.setValue(request.getValue());
        d.setUpdatedAt(LocalDateTime.now());
        d.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        markupDefaultRepository.save(d);

        auditService.recordUpdate(AuditEntityType.MARKUP_DEFAULT, serviceType.name(),
                serviceType.name() + " markup default", List.of());
        log.info("Markup default set: serviceType={}, mode={}, value={}", serviceType, d.getMode(), d.getValue());
        return MarkupDefaultResponse.builder().serviceType(serviceType).mode(d.getMode()).value(d.getValue()).build();
    }
}
