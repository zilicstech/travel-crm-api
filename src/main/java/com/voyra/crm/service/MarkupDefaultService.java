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
