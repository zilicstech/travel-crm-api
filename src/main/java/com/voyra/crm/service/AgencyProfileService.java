package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in owner's own agency profile - the fields Account/Settings needs that
 * {@link AgencyService} (platform-side provisioning) does not expose: GST number, address,
 * currency, default commission, logo. The tenant row's id doubles as the owner's own user id,
 * so "my agency" is always {@code tenantRepository.findById(principal.userId())}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyProfileService {

    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public AgencyProfileResponse getProfile() {
        return toResponse(findOwnTenant());
    }

    @Transactional
    public AgencyProfileResponse updateProfile(AgencyProfileUpdateRequest request) {
        Tenant tenant = findOwnTenant();
        if (request.getGstNumber() != null) {
            tenant.setGstNumber(request.getGstNumber());
        }
        if (request.getAddress() != null) {
            tenant.setAddress(request.getAddress());
        }
        if (request.getCurrency() != null) {
            tenant.setCurrency(request.getCurrency());
        }
        if (request.getDefaultCommission() != null) {
            tenant.setDefaultCommission(request.getDefaultCommission());
        }
        if (request.getLogoKey() != null) {
            tenant.setLogoKey(request.getLogoKey());
        }
        tenantRepository.save(tenant);
        log.info("Agency profile updated: tenantId={}", tenant.getId());
        return toResponse(tenant);
    }

    private Tenant findOwnTenant() {
        String ownerId = SecurityContextUtil.getCurrentUserOrThrow().userId();
        return tenantRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + ownerId));
    }

    private AgencyProfileResponse toResponse(Tenant t) {
        return AgencyProfileResponse.builder()
                .agencyName(t.getAgencyName()).ownerName(t.getOwnerName()).ownerEmail(t.getOwnerEmail())
                .gstNumber(t.getGstNumber()).address(t.getAddress()).currency(t.getCurrency())
                .defaultCommission(t.getDefaultCommission()).logoKey(t.getLogoKey())
                .build();
    }
}
