package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySettingCreateRequest;
import com.voyra.crm.dto.AgencySettingResponse;
import com.voyra.crm.dto.AgencySettingUpdateRequest;
import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgencySettingRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agency-editable configuration - what used to live only in the frontend's localStorage.
 * Lead sources and travel categories are matched by name onto {@code lead.source}/
 * {@code lead.categories}, which are free text now rather than fixed enums; service
 * preferences are matched by name onto {@code lead_service.preferences}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencySettingService {

    private final AgencySettingRepository agencySettingRepository;

    @Transactional
    public AgencySettingResponse create(AgencySettingCreateRequest request) {
        boolean requiresServiceType = request.getKind() == AgencySettingKind.SERVICE_PREFERENCE
                || request.getKind() == AgencySettingKind.SUPPLIER;
        if (requiresServiceType && request.getServiceType() == null) {
            throw new IllegalArgumentException("serviceType is required for a " + request.getKind() + " setting");
        }
        boolean duplicate = request.getServiceType() != null
                ? agencySettingRepository.existsByKindAndServiceTypeAndNameIgnoreCase(
                        request.getKind(), request.getServiceType(), request.getName())
                : agencySettingRepository.existsByKindAndNameIgnoreCase(request.getKind(), request.getName());
        if (duplicate) {
            throw new IllegalStateException("\"" + request.getName() + "\" already exists");
        }

        AgencySetting setting = AgencySetting.builder()
                .id(UniqueIdResolver.resolve(agencySettingRepository::existsById))
                .kind(request.getKind())
                .serviceType(request.getServiceType())
                .name(request.getName())
                .isActive(true)
                .isDefault(false)
                .sortOrder(nextSortOrder(request.getKind(), request.getServiceType()))
                .createdAt(LocalDateTime.now())
                .build();
        agencySettingRepository.save(setting);
        log.info("Agency setting created: kind={}, name={}", request.getKind(), request.getName());
        return toResponse(setting);
    }

    @Transactional(readOnly = true)
    public List<AgencySettingResponse> list(AgencySettingKind kind, ServiceType serviceType) {
        List<AgencySetting> rows = serviceType != null
                ? agencySettingRepository.findByKindAndServiceTypeOrderBySortOrderAsc(kind, serviceType)
                : agencySettingRepository.findByKindOrderBySortOrderAsc(kind);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public AgencySettingResponse update(String id, AgencySettingUpdateRequest request) {
        AgencySetting setting = agencySettingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Setting not found: " + id));
        if (request.getName() != null) {
            setting.setName(request.getName());
        }
        if (request.getIsActive() != null) {
            setting.setIsActive(request.getIsActive());
        }
        if (request.getSortOrder() != null) {
            setting.setSortOrder(request.getSortOrder());
        }
        agencySettingRepository.save(setting);
        return toResponse(setting);
    }

    @Transactional
    public void delete(String id) {
        AgencySetting setting = agencySettingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Setting not found: " + id));
        agencySettingRepository.delete(setting);
        log.info("Agency setting removed: id={}", id);
    }

    private int nextSortOrder(AgencySettingKind kind, ServiceType serviceType) {
        List<AgencySetting> existing = serviceType != null
                ? agencySettingRepository.findByKindAndServiceTypeOrderBySortOrderAsc(kind, serviceType)
                : agencySettingRepository.findByKindOrderBySortOrderAsc(kind);
        return existing.stream().mapToInt(AgencySetting::getSortOrder).max().orElse(-1) + 1;
    }

    private AgencySettingResponse toResponse(AgencySetting s) {
        return AgencySettingResponse.builder()
                .id(s.getId()).kind(s.getKind()).serviceType(s.getServiceType()).name(s.getName())
                .isActive(s.getIsActive()).isDefault(s.getIsDefault()).sortOrder(s.getSortOrder())
                .build();
    }
}
