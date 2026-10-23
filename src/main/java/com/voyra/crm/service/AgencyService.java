package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.AgencyUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.dto.PasswordResetRequest;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.migration.TenantFlywayMigrator;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Platform (SUPER_ADMIN) management of Agencies. Agency creation = tenant provisioning. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyService {

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final AesPasswordEncoder passwordEncoder;
    private final TenantFlywayMigrator tenantFlywayMigrator;
    private final TenantScopedReadService tenantScopedReadService;
    private final TenantDefaultsSeeder tenantDefaultsSeeder;
    private final ChartOfAccountsSeedService chartOfAccountsSeedService;
    private final LoginEmailService loginEmailService;

    @Transactional
    public AgencyCreateResponse createAgency(AgencyCreateRequest request) {
        loginEmailService.assertFree(request.getOwnerEmail());

        Tenant tenant = Tenant.builder()
                .id(generateUniqueTenantId())
                .agencyName(request.getAgencyName())
                .ownerName(request.getOwnerName())
                .ownerEmail(request.getOwnerEmail())
                .password(passwordEncoder.encode(request.getOwnerPassword()))
                .isActive(true)
                .baseCurrencyCode(com.voyra.crm.util.FxMath.code(request.getBaseCurrencyCode(), BaseCurrency.DEFAULT))
                .build();
        provision(tenant);

        log.info("Agency created: tenantId={}, agencyName={}", tenant.getId(), tenant.getAgencyName());
        return AgencyCreateResponse.builder()
                .id(tenant.getId())
                .agencyName(tenant.getAgencyName())
                .ownerName(tenant.getOwnerName())
                .ownerEmail(tenant.getOwnerEmail())
                .baseCurrencyCode(tenant.getBaseCurrencyCode())
                .build();
    }

    /**
     * An approved application becomes an agency: its KYC identity is copied onto the tenant and the
     * caller (a SUPER_ADMIN) is recorded as the approver. The caller marks the application APPROVED.
     */
    @Transactional
    public Tenant provisionFromApplication(AgencyApplication app, String ownerPassword) {
        loginEmailService.assertFree(app.getOwnerEmail());
        Tenant tenant = Tenant.builder()
                .id(generateUniqueTenantId())
                .agencyName(app.getAgencyName())
                .legalName(app.getLegalName())
                .tradeName(app.getTradeName())
                .pan(app.getPan())
                .cin(app.getCin())
                .gstNumber(app.getGstin())
                .address(app.getAddress())
                .stateCode(app.getStateCode())
                .ownerName(app.getOwnerName())
                .ownerEmail(app.getOwnerEmail())
                .password(passwordEncoder.encode(ownerPassword))
                .baseCurrencyCode(com.voyra.crm.util.FxMath.code(app.getBaseCurrencyCode(), BaseCurrency.DEFAULT))
                .approvedAt(Instant.now())
                .approvedBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        tenant.setStatus(TenantStatus.ACTIVE);
        provision(tenant);
        log.info("Agency provisioned from application: tenantId={}, applicationId={}", tenant.getId(), app.getId());
        return tenant;
    }

    /** Agency creation = tenant provisioning: the row, its schema, its defaults, then the auth cache. */
    private void provision(Tenant tenant) {
        // Flush first: a concurrent create with the same owner email (a double-click) waits on the
        // unique index here and fails before it builds a schema, instead of leaving an orphan one.
        tenantRepository.saveAndFlush(tenant);
        tenantFlywayMigrator.migrate(tenant.getId());
        seedDefaultsForTenant(tenant.getId());
        seedChartOfAccountsFor(tenant.getId());
        TenantCache.put(tenant.getId(), tenant.getAgencyName(), tenant.getStatus());
    }

    @Transactional(readOnly = true)
    public List<AgencyResponse> listAgencies() {
        return tenantRepository.findAll().stream()
                .map(t -> toResponse(t, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgencyResponse getAgency(String id) {
        Tenant tenant = findById(id);
        return toResponse(tenant, true);
    }

    @Transactional
    public AgencyResponse updateStatus(String id, TenantStatus status) {
        Tenant tenant = findById(id);
        tenant.setStatus(status);
        tenantRepository.save(tenant);
        // Same-method cache sync: a stale auth cache is a security bug (blueprint §8.10 rule 2).
        TenantCache.put(tenant.getId(), tenant.getAgencyName(), status);
        log.info("Agency status updated: tenantId={}, status={}", id, status);
        // includeRevenue=true - a caller updating one agency's status in place (the detail
        // modal) would otherwise see its revenue silently reset to zero on the very next
        // render, since a null totalRevenue reads as 0 wherever this response gets cached.
        return toResponse(tenant, true);
    }

    @Transactional(readOnly = true)
    public CredentialsResponse getCredentials(String id) {
        var principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isSuperAdmin()) {
            throw new AccessDeniedException("Only platform admins can retrieve agency credentials");
        }
        Tenant tenant = findById(id);
        log.info("Agency owner credentials retrieved: tenantId={}, by={}", id, SecurityContextUtil.getAuditInfo());
        return CredentialsResponse.builder()
                .id(tenant.getId())
                .email(tenant.getOwnerEmail())
                .password(passwordEncoder.decode(tenant.getPassword()))
                .build();
    }

    @Transactional
    public AgencyResponse updateAgency(String id, AgencyUpdateRequest request) {
        Tenant tenant = findById(id);
        if (request.getAgencyName() != null && !request.getAgencyName().isBlank()) {
            tenant.setAgencyName(request.getAgencyName().trim());
        }
        if (request.getOwnerName() != null && !request.getOwnerName().isBlank()) {
            tenant.setOwnerName(request.getOwnerName().trim());
        }
        if (request.getOwnerEmail() != null && !request.getOwnerEmail().isBlank()) {
            String trimmedEmail = request.getOwnerEmail().trim();
            if (!trimmedEmail.equalsIgnoreCase(tenant.getOwnerEmail())) {
                loginEmailService.assertFree(trimmedEmail);
                tenant.setOwnerEmail(trimmedEmail);
            }
        }
        tenantRepository.save(tenant);
        TenantCache.put(tenant.getId(), tenant.getAgencyName(), tenant.getStatus());
        log.info("Agency updated: tenantId={}, agencyName={}", id, tenant.getAgencyName());
        return toResponse(tenant, true);
    }

    @Transactional
    public void resetOwnerPassword(String id, PasswordResetRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirm password do not match");
        }
        Tenant tenant = findById(id);
        tenant.setPassword(passwordEncoder.encode(request.getNewPassword()));
        tenantRepository.save(tenant);
        log.info("Agency owner password reset: tenantId={}, by={}", id, SecurityContextUtil.getAuditInfo());
    }

    /** Package-visible so an approved application can answer with the agency it created. */
    AgencyResponse toResponse(Tenant tenant, boolean includeRevenue) {
        // Agents only: accountants share the table but are not agents.
        long agentsCount = agentRepository.countByTenantIdAndUserRole(tenant.getId(), com.voyra.crm.enums.UserType.AGENT);
        BigDecimal totalRevenue = null;
        if (includeRevenue) {
            totalRevenue = readRevenueForTenant(tenant.getId());
        }
        return AgencyResponse.builder()
                .id(tenant.getId())
                .agencyName(tenant.getAgencyName())
                .ownerName(tenant.getOwnerName())
                .ownerEmail(tenant.getOwnerEmail())
                .isActive(tenant.getIsActive())
                .status(tenant.getStatus())
                .agentsCount(agentsCount)
                .totalRevenue(totalRevenue)
                .createdDate(tenant.getCreatedDate())
                .baseCurrencyCode(tenant.getBaseCurrencyCode())
                .build();
    }

    /**
     * Cross-tenant write (blueprint §3.5): switch context, seed in a fresh REQUIRES_NEW transaction,
     * always restore. Not fatal - the startup seed runners repair any tenant that is missed here, and
     * failing the whole create would leave an orphan migrated schema behind.
     */
    private void seedDefaultsForTenant(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            tenantDefaultsSeeder.seedCurrentTenant();
        } catch (RuntimeException e) {
            log.error("Could not seed default settings for new agency {}; they will be seeded on the next restart",
                    tenantId, e);
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    /**
     * Cross-tenant write (blueprint §3.5): switch context, seed in a fresh REQUIRES_NEW
     * transaction, always restore - this method's own {@code createAgency} caller is already
     * inside a public-schema transaction, so {@link ChartOfAccountsSeedService#seed} must be a
     * genuinely separate bean or self-invocation would bypass the proxy and seed the wrong schema.
     */
    private void seedChartOfAccountsFor(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            chartOfAccountsSeedService.seed();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    /** Cross-tenant read (blueprint §3.5): switch context, read in a fresh REQUIRES_NEW transaction, always restore. */
    private BigDecimal readRevenueForTenant(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            return tenantScopedReadService.sumTotalBookingRevenue();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    private Tenant findById(String id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agency not found: " + id));
    }

    private String generateUniqueTenantId() {
        return UniqueIdResolver.resolve(tenantRepository::existsById);
    }
}
