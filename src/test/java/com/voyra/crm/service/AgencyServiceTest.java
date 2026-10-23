package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
