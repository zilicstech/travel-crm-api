package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.AgencyUpdateRequest;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.migration.TenantFlywayMigrator;
import com.voyra.crm.repository.WalletRepository;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    @Mock private WalletRepository walletRepository;
    @Mock private com.voyra.crm.repository.SubscriptionRepository subscriptionRepository;
    @Mock private TenantFlywayMigrator tenantFlywayMigrator;
    @Mock private TenantDefaultsSeeder tenantDefaultsSeeder;
    @Mock private ChartOfAccountsSeedService chartOfAccountsSeedService;
    @Mock private LoginEmailService loginEmailService;
    @Mock private com.voyra.crm.security.AesPasswordEncoder passwordEncoder;
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
    void settingTheSameStatusAgainWritesNoAuditRow() {
        Tenant tenant = Tenant.builder().id(ID).agencyName("A").status(TenantStatus.ACTIVE).build();
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(tenant));

        agencyService.updateStatus(ID, TenantStatus.ACTIVE);

        verify(platformAuditService, never()).record(any(), any(), any(), any());
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

    @Test
    void provisioningCreatesAnOpenZeroWallet() {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName("A");
        request.setOwnerName("O");
        request.setOwnerEmail("o@a.test");
        request.setOwnerPassword("pw");

        var created = agencyService.createAgency(request);

        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(saved.capture());
        Wallet wallet = saved.getValue();
        assertThat(wallet.getTenantId()).isEqualTo(created.getId());
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getHeld()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.OPEN);
        assertThat(wallet.getIsTest()).isFalse();
        TenantCache.put(created.getId(), "A", TenantStatus.ACTIVE);
    }

    @Test
    void aStatusChangeReachesTheCacheOnlyAfterCommit() {
        TenantCache.put(ID, "A", TenantStatus.ACTIVE);
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(
                Tenant.builder().id(ID).agencyName("A").status(TenantStatus.ACTIVE).build()));
        TransactionSynchronizationManager.initSynchronization();
        try {
            agencyService.updateStatus(ID, TenantStatus.SUSPENDED);
            assertThat(TenantCache.status(ID)).contains(TenantStatus.ACTIVE); // not yet committed
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(TenantCache.status(ID)).contains(TenantStatus.ACTIVE);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(TenantCache.status(ID)).contains(TenantStatus.SUSPENDED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRenameReachesTheCacheOnlyAfterCommit() {
        TenantCache.put(ID, "A", TenantStatus.SUSPENDED);
        when(tenantRepository.findById(ID)).thenReturn(Optional.of(
                Tenant.builder().id(ID).agencyName("A").status(TenantStatus.ACTIVE).build()));
        AgencyUpdateRequest request = new AgencyUpdateRequest();
        request.setAgencyName("B");
        TransactionSynchronizationManager.initSynchronization();
        try {
            agencyService.updateAgency(ID, request);
            assertThat(TenantCache.status(ID)).contains(TenantStatus.SUSPENDED);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(TenantCache.status(ID)).contains(TenantStatus.ACTIVE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aNewAgencyReachesTheCacheWithItsEntitlementsOnlyAfterCommit() {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName("A");
        request.setOwnerName("O");
        request.setOwnerEmail("o@a.test");
        request.setOwnerPassword("pw");
        TransactionSynchronizationManager.initSynchronization();
        String id = null;
        try {
            id = agencyService.createAgency(request).getId();
            assertThat(TenantCache.isValidTenant(id)).isFalse();
            assertThat(TenantCache.entitlements(id)).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(TenantCache.status(id)).contains(TenantStatus.ACTIVE);
            assertThat(TenantCache.entitlements(id)).isPresent();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TenantCache.remove(id);
        }
    }
}
