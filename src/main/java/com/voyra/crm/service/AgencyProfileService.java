package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UploadValidator;
import com.voyra.crm.util.ValidationPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

    static final String LOGO_CATEGORY = "logo";

    private final TenantRepository tenantRepository;
    private final FileStorageService fileStorageService;

    /** Not a DTO - unpacked into an image response by the controller. */
    public record Logo(Resource resource, String contentType) {
    }

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

    /** Owner: PNG, JPEG or WebP up to 1 MB replaces the logo; the previous logo file is deleted. */
    @Transactional
    public AgencyProfileResponse uploadLogo(MultipartFile file) {
        UploadValidator.assertLogo(file);
        Tenant tenant = findOwnTenant();
        String previous = tenant.getLogoKey();
        tenant.setLogoKey(fileStorageService.store(tenant.getId(), LOGO_CATEGORY, tenant.getId(), file));
        tenantRepository.save(tenant);
        deleteOwnLogoFile(previous);
        log.info("Agency logo uploaded: tenantId={}", tenant.getId());
        return toResponse(tenant);
    }

    @Transactional
    public AgencyProfileResponse removeLogo() {
        Tenant tenant = findOwnTenant();
        String previous = tenant.getLogoKey();
        tenant.setLogoKey(null);
        tenantRepository.save(tenant);
        deleteOwnLogoFile(previous);
        log.info("Agency logo removed: tenantId={}", tenant.getId());
        return toResponse(tenant);
    }

    /** Any user of the agency; 404 when it has none. */
    @Transactional(readOnly = true)
    public Logo logo() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String key = tenantId == null ? null : tenantRepository.findById(tenantId).map(Tenant::getLogoKey).orElse(null);
        if (key == null || key.isBlank()) {
            throw new NotFoundException("This agency has no logo");
        }
        String type = UploadValidator.logoContentType(key);
        return new Logo(fileStorageService.retrieve(key), type != null ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    /**
     * Only a file this endpoint stored: a logo carried over from onboarding is the application's KYC document file, which
     * approval keeps as KYC evidence. A failed delete leaves an orphan file, never a failed request.
     */
    private void deleteOwnLogoFile(String key) {
        if (key == null || !key.contains("/" + LOGO_CATEGORY + "/")) {
            return;
        }
        try {
            fileStorageService.delete(key);
        } catch (RuntimeException e) {
            log.warn("Old agency logo not deleted: key={}", key, e);
        }
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
                .hasLogo(t.getLogoKey() != null && !t.getLogoKey().isBlank())
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
