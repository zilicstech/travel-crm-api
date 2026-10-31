package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.AgencyUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.dto.PasswordResetRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.migration.TenantFlywayMigrator;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Agency list, credentials, edits, password reset and the tenant-context restore around provisioning. */
@ExtendWith(MockitoExtension.class)
class AgencyServiceCoverageTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AesPasswordEncoder passwordEncoder;
    @Mock private TenantFlywayMigrator tenantFlywayMigrator;
    @Mock private TenantScopedReadService tenantScopedReadService;
    @Mock private TenantDefaultsSeeder tenantDefaultsSeeder;
    @Mock private ChartOfAccountsSeedService chartOfAccountsSeedService;
    @Mock private LoginEmailService loginEmailService;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private AgencyApplicationRepository agencyApplicationRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OwnerNameSyncService ownerNameSyncService;
    @InjectMocks private AgencyService service;

    private final List<String> cached = new ArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        cached.forEach(TenantCache::remove);
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, null), null, List.of()));
    }

    private Tenant tenant(String id) {
        cached.add(id);
        Tenant t = Tenant.builder().id(id).agencyName("Sky Travels").ownerName("Owner").ownerEmail("owner@example.test")
                .password("enc").build();
        when(tenantRepository.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void theListCountsAgentsButSkipsRevenueAndApplicationLookups() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").agencyName("Sky").build()));
        when(agentRepository.countByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(4L);

        List<AgencyResponse> out = service.listAgencies();

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.getAgentsCount()).isEqualTo(4L);
            assertThat(r.getTotalRevenue()).isNull();
            assertThat(r.getApplicationId()).isNull();
        });
        verifyNoInteractions(tenantScopedReadService, agencyApplicationRepository);
    }

    @Test
    void anUnknownAgencyIsNotFound() {
        when(tenantRepository.findById("T9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAgency("T9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agency not found: T9");
    }

    @Test
    void theDetailReadsRevenueInTheAgencysSchemaAndRestoresTheCallersTenant() {
        tenant("T1");
        TenantContext.setTenantId("OUTER");
        doAnswer(i -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("T1");
            return new BigDecimal("125000.00");
        }).when(tenantScopedReadService).sumTotalBookingRevenue();

        AgencyResponse r = service.getAgency("T1");

        assertThat(r.getTotalRevenue()).isEqualByComparingTo("125000.00");
        assertThat(TenantContext.getTenantId()).isEqualTo("OUTER");
    }

    @Test
    void onlyAPlatformAdminGetsTheOwnersCredentials() {
        as("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> service.getCredentials("T1"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("Only platform admins can retrieve agency credentials");
        verifyNoInteractions(tenantRepository, passwordEncoder);

        as("SA", UserType.SUPER_ADMIN);
        tenant("T1");
        when(passwordEncoder.decode("enc")).thenReturn("plain-test-pass");
        CredentialsResponse c = service.getCredentials("T1");
        assertThat(c.getId()).isEqualTo("T1");
        assertThat(c.getEmail()).isEqualTo("owner@example.test");
        assertThat(c.getPassword()).isEqualTo("plain-test-pass");
    }

    @Test
    void ownerRenameResyncsSnapshotsOnlyWhenTheNameChanged() {
        tenant("T1");
        AgencyUpdateRequest same = new AgencyUpdateRequest();
        same.setOwnerName(" Owner ");

        service.updateAgency("T1", same);

        verifyNoInteractions(ownerNameSyncService);
    }

    @Test
    void anEditTrimsNamesAndChecksANewEmailOnly() {
        Tenant t = tenant("T1");
        AgencyUpdateRequest r = new AgencyUpdateRequest();
        r.setAgencyName("  Sea Travels ");
        r.setOwnerName(" New Owner ");
        r.setOwnerEmail(" OWNER@example.test ");

        service.updateAgency("T1", r);
        assertThat(t.getAgencyName()).isEqualTo("Sea Travels");
        assertThat(t.getOwnerName()).isEqualTo("New Owner");
        assertThat(t.getOwnerEmail()).isEqualTo("owner@example.test");
        verify(ownerNameSyncService).resync("T1", "New Owner");
        verify(loginEmailService, never()).assertFree(any());
        assertThat(TenantCache.isValidTenant("T1")).isTrue();

        AgencyUpdateRequest email = new AgencyUpdateRequest();
        email.setAgencyName(" ");
        email.setOwnerName(" ");
        email.setOwnerEmail("new@example.test");
        service.updateAgency("T1", email);
        verify(loginEmailService).assertFree("new@example.test");
        assertThat(t.getOwnerEmail()).isEqualTo("new@example.test");
        assertThat(t.getAgencyName()).isEqualTo("Sea Travels");

        AgencyUpdateRequest blankEmail = new AgencyUpdateRequest();
        blankEmail.setOwnerEmail("  ");
        service.updateAgency("T1", blankEmail);
        assertThat(t.getOwnerEmail()).isEqualTo("new@example.test");
        verify(tenantRepository, org.mockito.Mockito.times(3)).save(t);
    }

    @Test
    void aTakenEmailIsRefusedAndNothingIsSaved() {
        tenant("T1");
        doThrow(new IllegalStateException("Email already in use")).when(loginEmailService).assertFree("taken@example.test");
        AgencyUpdateRequest r = new AgencyUpdateRequest();
        r.setOwnerEmail("taken@example.test");
        assertThatThrownBy(() -> service.updateAgency("T1", r)).hasMessage("Email already in use");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void thePasswordResetNeedsMatchingEntriesAndStoresTheEncodedOne() {
        PasswordResetRequest mismatch = new PasswordResetRequest();
        mismatch.setNewPassword("a-test-pass-1");
        mismatch.setConfirmPassword("a-test-pass-2");
        assertThatThrownBy(() -> service.resetOwnerPassword("T1", mismatch))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("New password and confirm password do not match");
        verifyNoInteractions(tenantRepository);

        Tenant t = tenant("T1");
        when(passwordEncoder.encode("a-test-pass-1")).thenReturn("enc-new");
        PasswordResetRequest ok = new PasswordResetRequest();
        ok.setNewPassword("a-test-pass-1");
        ok.setConfirmPassword("a-test-pass-1");
        service.resetOwnerPassword("T1", ok);
        assertThat(t.getPassword()).isEqualTo("enc-new");
        verify(tenantRepository).save(t);
    }

    @Test
    void aFailedDefaultsSeedDoesNotStopTheAgencyAndTheCallersTenantIsRestored() {
        when(passwordEncoder.encode("p")).thenReturn("enc");
        doThrow(new IllegalStateException("seed failed")).when(tenantDefaultsSeeder).seedCurrentTenant();
        doAnswer(i -> {
            assertThat(TenantContext.getTenantId()).isNotEqualTo("OUTER");
            return null;
        }).when(chartOfAccountsSeedService).seed();
        TenantContext.setTenantId("OUTER");
        AgencyCreateRequest r = new AgencyCreateRequest();
        r.setAgencyName("Sky");
        r.setOwnerEmail("o@example.test");
        r.setOwnerPassword("p");

        AgencyCreateResponse out = service.createAgency(r);
        cached.add(out.getId());

        assertThat(out.getBaseCurrencyCode()).isEqualTo("INR");
        assertThat(TenantContext.getTenantId()).isEqualTo("OUTER");
        verify(chartOfAccountsSeedService).seed();
        ArgumentCaptor<Tenant> saved = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).saveAndFlush(saved.capture());
        assertThat(TenantCache.status(saved.getValue().getId())).contains(TenantStatus.ACTIVE);
    }

    @Test
    void aBlankCallerTenantIsClearedAfterProvisioning() {
        when(passwordEncoder.encode("p")).thenReturn("enc");
        TenantContext.setTenantId(" ");
        AgencyCreateRequest r = new AgencyCreateRequest();
        r.setAgencyName("Sky");
        r.setOwnerEmail("o@example.test");
        r.setOwnerPassword("p");

        cached.add(service.createAgency(r).getId());

        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void aBlankCallerTenantIsClearedAfterTheRevenueRead() {
        tenant("T1");
        TenantContext.setTenantId(" ");
        when(tenantScopedReadService.sumTotalBookingRevenue()).thenReturn(BigDecimal.ZERO);

        service.getAgency("T1");

        assertThat(TenantContext.getTenantId()).isNull();
    }
}
