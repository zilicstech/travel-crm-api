package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.ValidationPatterns;
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
            tenant.setGstNumber(request.getGstNumber().trim().toUpperCase());
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
        if (request.getStateCode() != null) {
            tenant.setStateCode(request.getStateCode());
        }
        // Only when the pair is being edited, so an unrelated edit isn't blocked by older data.
        if (request.getGstNumber() != null || request.getStateCode() != null) {
            ValidationPatterns.requireGstinMatchesState(tenant.getGstNumber(), tenant.getStateCode());
        }
        // Every amount in the books is in it, so it is set once, when the agency is created.
        if (request.getBaseCurrencyCode() != null && !request.getBaseCurrencyCode().isBlank()
                && !request.getBaseCurrencyCode().trim().equalsIgnoreCase(tenant.getBaseCurrencyCode())) {
            throw new IllegalArgumentException("The base currency is set when the agency is created and can't be changed");
        }
        if (request.getDefaultSacCode() != null) {
            tenant.setDefaultSacCode(request.getDefaultSacCode());
        }
        if (request.getInvoiceTerms() != null) {
            tenant.setInvoiceTerms(request.getInvoiceTerms());
        }
        if (request.getLegalName() != null) {
            tenant.setLegalName(request.getLegalName());
        }
        if (request.getBankAccountName() != null) {
            tenant.setBankAccountName(request.getBankAccountName());
        }
        if (request.getBankAccountNumber() != null) {
            tenant.setBankAccountNumber(request.getBankAccountNumber());
        }
        if (request.getBankIfscCode() != null) {
            tenant.setBankIfscCode(request.getBankIfscCode().trim().toUpperCase());
        }
        if (request.getBankBranch() != null) {
            tenant.setBankBranch(request.getBankBranch());
        }
        if (request.getContactPhone() != null) {
            tenant.setContactPhone(blankToNull(request.getContactPhone()));
        }
        if (request.getContactEmail() != null) {
            tenant.setContactEmail(blankToNull(request.getContactEmail()));
        }
        if (request.getWebsite() != null) {
            tenant.setWebsite(blankToNull(request.getWebsite()));
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
                .stateCode(t.getStateCode()).baseCurrencyCode(t.getBaseCurrencyCode())
                .defaultSacCode(t.getDefaultSacCode()).invoiceTerms(t.getInvoiceTerms())
                .legalName(t.getLegalName())
                .bankAccountName(t.getBankAccountName()).bankAccountNumber(t.getBankAccountNumber())
                .bankIfscCode(t.getBankIfscCode()).bankBranch(t.getBankBranch())
                .contactPhone(t.getContactPhone()).contactEmail(t.getContactEmail()).website(t.getWebsite())
                .build();
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
