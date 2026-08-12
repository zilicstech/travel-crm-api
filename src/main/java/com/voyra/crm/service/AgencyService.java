package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.migration.TenantFlywayMigrator;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.RandomPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    @Transactional
    public AgencyCreateResponse createAgency(AgencyCreateRequest request) {
        if (tenantRepository.existsByOwnerEmailIgnoreCase(request.getOwnerEmail())) {
            throw new IllegalArgumentException("An agency with this owner email already exists");
        }

        String rawPassword = RandomPasswordGenerator.generate();
        Tenant tenant = Tenant.builder()
                .id(generateUniqueTenantId())
                .agencyName(request.getAgencyName())
                .ownerName(request.getOwnerName())
                .ownerEmail(request.getOwnerEmail())
                .password(passwordEncoder.encode(rawPassword))
                .isActive(true)
                .build();

        tenantRepository.save(tenant);
        TenantCache.put(tenant.getId(), tenant.getAgencyName(), true);
        tenantFlywayMigrator.migrate(tenant.getId());

        log.info("Agency created: tenantId={}, agencyName={}", tenant.getId(), tenant.getAgencyName());
        return AgencyCreateResponse.builder()
                .id(tenant.getId())
                .agencyName(tenant.getAgencyName())
                .ownerName(tenant.getOwnerName())
                .ownerEmail(tenant.getOwnerEmail())
                .initialPassword(rawPassword)
                .build();
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
    public AgencyResponse updateStatus(String id, boolean isActive) {
        Tenant tenant = findById(id);
        tenant.setIsActive(isActive);
        tenantRepository.save(tenant);
        // Same-method cache sync: a stale auth cache is a security bug (blueprint §8.10 rule 2).
        TenantCache.put(tenant.getId(), tenant.getAgencyName(), isActive);
        log.info("Agency status updated: tenantId={}, isActive={}", id, isActive);
        return toResponse(tenant, false);
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

    private AgencyResponse toResponse(Tenant tenant, boolean includeRevenue) {
        long agentsCount = agentRepository.findByTenantId(tenant.getId()).size();
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
                .agentsCount(agentsCount)
                .totalRevenue(totalRevenue)
                .createdDate(tenant.getCreatedDate())
                .build();
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
