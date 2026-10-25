package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.migration.TenantFlywayMigrator;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Both ways in - the platform's create form and an approved application - provision a tenant the same way. */
@ExtendWith(MockitoExtension.class)
class AgencyServiceProvisionTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AesPasswordEncoder passwordEncoder;
    @Mock private TenantFlywayMigrator tenantFlywayMigrator;
    @Mock private TenantScopedReadService tenantScopedReadService;
    @Mock private TenantDefaultsSeeder tenantDefaultsSeeder;
    @Mock private ChartOfAccountsSeedService chartOfAccountsSeedService;
    @Mock private LoginEmailService loginEmailService;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private com.voyra.crm.repository.WalletRepository walletRepository;
    @Mock private com.voyra.crm.repository.AgencyApplicationRepository agencyApplicationRepository;
    @InjectMocks private AgencyService agencyService;

    @BeforeEach
    void signInAsPlatformAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("root-1", "root", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    private final List<String> provisioned = new ArrayList<>();

    @AfterEach
    void signOutAndForgetTheTenants() {
        SecurityContextHolder.clearContext();
        provisioned.forEach(TenantCache::remove);
    }

    @Test
    void provisionFromApplicationChecksTheEmailMigratesAndCaches() {
        AgencyApplication app = AgencyApplication.builder().id("app-1").status(ApplicationStatus.SUBMITTED)
                .agencyName("Sky Trips").legalName("Sky Trips Private Limited").tradeName("Sky Trips Holidays")
                .pan("ABCDE1234F").gstin("29ABCDE1234F1Z5").cin("U63040KA2020PTC123456").address("12 MG Road")
                .stateCode("29").ownerName("Asha Rao").ownerEmail("asha@skytrips.in").ownerPhone("+91 98765 43210")
                .baseCurrencyCode("INR").build();
        when(passwordEncoder.encode("Secret123")).thenReturn("enc");

        Tenant tenant = agencyService.provisionFromApplication(app, "Secret123");
        provisioned.add(tenant.getId());

        InOrder order = inOrder(loginEmailService, tenantRepository, tenantFlywayMigrator, chartOfAccountsSeedService);
        order.verify(loginEmailService).assertFree("asha@skytrips.in");
        order.verify(tenantRepository).saveAndFlush(tenant);
        order.verify(tenantFlywayMigrator).migrate(tenant.getId());
        order.verify(chartOfAccountsSeedService).seed();
        verify(tenantDefaultsSeeder).seedCurrentTenant();
        assertThat(TenantCache.status(tenant.getId())).contains(TenantStatus.ACTIVE);
        assertThat(tenant.getId()).hasSize(36);
        assertThat(tenant.getAgencyName()).isEqualTo("Sky Trips");
        assertThat(tenant.getLegalName()).isEqualTo("Sky Trips Private Limited");
        assertThat(tenant.getTradeName()).isEqualTo("Sky Trips Holidays");
        assertThat(tenant.getPan()).isEqualTo("ABCDE1234F");
        assertThat(tenant.getCin()).isEqualTo("U63040KA2020PTC123456");
        assertThat(tenant.getGstNumber()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(tenant.getAddress()).isEqualTo("12 MG Road");
        assertThat(tenant.getStateCode()).isEqualTo("29");
        assertThat(tenant.getOwnerName()).isEqualTo("Asha Rao");
        assertThat(tenant.getOwnerEmail()).isEqualTo("asha@skytrips.in");
        assertThat(tenant.getPassword()).isEqualTo("enc");
        assertThat(tenant.getContactPhone()).isEqualTo("+91 98765 43210");
        assertThat(tenant.getContactEmail()).isEqualTo("asha@skytrips.in");
        assertThat(tenant.getBaseCurrencyCode()).isEqualTo("INR");
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.getIsActive()).isTrue();
        assertThat(tenant.getApprovedBy()).isEqualTo("root-1");
        assertThat(tenant.getApprovedAt()).isCloseTo(Instant.now(), within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void createAgencyStillWorksThroughTheSharedProvision() {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName("Global Explorer Travels");
        request.setOwnerName("John Davis");
        request.setOwnerEmail("owner@globalexplorer.com");
        request.setOwnerPassword("SecurePass123");
        request.setBaseCurrencyCode("usd");
        when(passwordEncoder.encode("SecurePass123")).thenReturn("enc");

        AgencyCreateResponse response = agencyService.createAgency(request);

        verify(loginEmailService).assertFree("owner@globalexplorer.com");
        ArgumentCaptor<Tenant> saved = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).saveAndFlush(saved.capture());
        Tenant tenant = saved.getValue();
        provisioned.add(tenant.getId());
        verify(tenantFlywayMigrator).migrate(tenant.getId());
        verify(tenantDefaultsSeeder).seedCurrentTenant();
        verify(chartOfAccountsSeedService).seed();
        assertThat(TenantCache.status(tenant.getId())).contains(TenantStatus.ACTIVE);
        assertThat(tenant.getApprovedAt()).isNull();
        assertThat(tenant.getPassword()).isEqualTo("enc");
        assertThat(response.getId()).isEqualTo(tenant.getId());
        assertThat(response.getAgencyName()).isEqualTo("Global Explorer Travels");
        assertThat(response.getOwnerEmail()).isEqualTo("owner@globalexplorer.com");
        assertThat(response.getBaseCurrencyCode()).isEqualTo("USD");
    }
}
