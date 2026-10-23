package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** updateStatus writes the status, keeps is_active mirrored, and syncs the auth cache in the same call. */
@ExtendWith(MockitoExtension.class)
class AgencyServiceTest {

    private static final String ID = "TAGSVC1";

    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private TenantScopedReadService tenantScopedReadService;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private AgencyApplicationRepository agencyApplicationRepository;
    @InjectMocks private AgencyService agencyService;

    @AfterEach
    void cleanCache() {
        TenantCache.put(ID, "A", TenantStatus.ACTIVE);
    }

    @Test
    void suspendingKeepsTheAgencyActiveForLegacyReadersAndUpdatesTheCache() {
        Tenant tenant = Tenant.builder().id(ID).agencyName("A").build();
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(tenant));

        AgencyResponse response = agencyService.updateStatus(ID, TenantStatus.SUSPENDED);

        assertThat(response.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(response.getIsActive()).isTrue();
        assertThat(TenantCache.status(ID)).contains(TenantStatus.SUSPENDED);
        verify(tenantRepository).save(tenant);
    }

    @Test
    void deactivatingFlipsIsActiveAndInvalidatesTheTenant() {
        Tenant tenant = Tenant.builder().id(ID).agencyName("A").build();
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(tenant));

        AgencyResponse response = agencyService.updateStatus(ID, TenantStatus.DEACTIVATED);

        assertThat(response.getIsActive()).isFalse();
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.DEACTIVATED);
        assertThat(TenantCache.isValidTenant(ID)).isFalse();
    }

    @Test
    void aStatusChangeIsWrittenToThePlatformAuditLog() {
        Tenant tenant = Tenant.builder().id(ID).agencyName("A").status(TenantStatus.ACTIVE).build();
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(tenant));

        agencyService.updateStatus(ID, TenantStatus.SUSPENDED);

        verify(platformAuditService).record(PlatformAuditAction.TENANT_STATUS, "TENANT", ID,
                Map.of("from", "ACTIVE", "to", "SUSPENDED"));
    }

    @Test
    void theDetailCarriesKycFieldsAndTheApplicationId() {
        Instant approvedAt = Instant.parse("2026-10-24T09:15:22Z");
        Tenant tenant = Tenant.builder().id(ID).agencyName("A").pan("ABCDE1234F").cin("U63040KA2020PTC123456")
                .approvedAt(approvedAt).build();
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(tenant));
        when(agencyApplicationRepository.findFirstByTenantId(ID))
                .thenReturn(Optional.of(AgencyApplication.builder().id("app-1").build()));

        AgencyResponse response = agencyService.getAgency(ID);

        assertThat(response.getPan()).isEqualTo("ABCDE1234F");
        assertThat(response.getCin()).isEqualTo("U63040KA2020PTC123456");
        assertThat(response.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(response.getApplicationId()).isEqualTo("app-1");
    }

    @Test
    void aDirectlyCreatedAgencyHasNoApplicationId() {
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(Tenant.builder().id(ID).agencyName("A").build()));
        when(agencyApplicationRepository.findFirstByTenantId(ID)).thenReturn(Optional.empty());

        AgencyResponse response = agencyService.getAgency(ID);

        assertThat(response.getApplicationId()).isNull();
        assertThat(response.getApprovedAt()).isNull();
    }
}
