package com.voyra.crm.config;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.ChartOfAccountsSeedService;
import com.voyra.crm.service.TenantDefaultsSeeder;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartupRunnersTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private ChartOfAccountsSeedService chartOfAccountsSeedService;
    @Mock private TenantDefaultsSeeder tenantDefaultsSeeder;
    @Mock private PasswordEncoder passwordEncoder;

    private final List<String> seenTenants = new ArrayList<>();
    private final DefaultApplicationArguments noArgs = new DefaultApplicationArguments();

    @AfterEach
    void clear() {
        TenantContext.clear();
        TenantCache.remove("CLT1");
        AgentCache.remove("CLA1");
        AgentCache.remove("CLA2");
    }

    private void twoTenants() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").build(), Tenant.builder().id("T2").build()));
    }

    /** Records the tenant in context, and fails on T2. */
    private Object seen() {
        seenTenants.add(TenantContext.getTenantId());
        if ("T2".equals(TenantContext.getTenantId())) throw new IllegalStateException("seed failed");
        return null;
    }

    @Test
    void cacheLoaderFillsTenantAgentAndAdminCachesFromTheDatabase() {
        Instant reset = Instant.parse("2026-10-01T00:00:00Z");
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("CLT1").agencyName("Agency").status(TenantStatus.SUSPENDED).build()));
        when(agentRepository.findAll()).thenReturn(List.of(
                Agent.builder().id("CLA1").isActive(true).passwordChangedAt(reset).build(),
                Agent.builder().id("CLA2").isActive(null).build()));
        when(platformAdminRepository.findAll()).thenReturn(List.of(
                PlatformAdmin.builder().id("CLP1").isActive(true).build(),
                PlatformAdmin.builder().id("CLP2").isActive(false).build()));

        try (LogCapture log = new LogCapture(CacheLoader.class)) {
            new CacheLoader(tenantRepository, agentRepository, platformAdminRepository).loadCaches();
            assertThat(log.lines()).containsExactly("INFO Caches loaded: 1 tenant(s), 2 agent(s), 2 platform admin(s)");
        }
        assertThat(TenantCache.status("CLT1")).contains(TenantStatus.SUSPENDED);
        assertThat(AgentCache.isActive("CLA1", Date.from(reset.plusSeconds(1)))).isTrue();
        assertThat(AgentCache.isActive("CLA1", Date.from(reset.minusSeconds(1)))).isFalse();
        assertThat(AgentCache.isActive("CLA2")).isFalse();
        assertThat(PlatformAdminCache.isActive("CLP1")).isTrue();
        assertThat(PlatformAdminCache.isActive("CLP2")).isFalse();
    }

    @Test
    void chartOfAccountsIsSeededPerTenantAndOneFailureDoesNotStopTheOthers() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T2").build(), Tenant.builder().id("T1").build()));
        doAnswer(inv -> seen()).when(chartOfAccountsSeedService).seed();

        try (LogCapture log = new LogCapture(ChartOfAccountsSeedRunner.class)) {
            new ChartOfAccountsSeedRunner(tenantRepository, chartOfAccountsSeedService).run(noArgs);
            assertThat(log.lines()).containsExactly("ERROR Chart of accounts seed failed for tenant T2: seed failed",
                    "INFO Chart of accounts seed pass complete for 2 tenant(s)");
        }
        assertThat(seenTenants).containsExactly("T2", "T1");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void tenantDefaultsAreSeededPerTenantAndFailuresCounted() {
        twoTenants();
        doAnswer(inv -> seen()).when(tenantDefaultsSeeder).seedCurrentTenant();

        try (LogCapture log = new LogCapture(TenantDefaultsStartupRunner.class)) {
            new TenantDefaultsStartupRunner(tenantRepository, tenantDefaultsSeeder).run(noArgs);
            assertThat(log.lines()).containsExactly("ERROR Could not seed defaults for tenant T2; will retry on next boot",
                    "INFO Tenant defaults checked for 2 tenant(s), 1 failed");
        }
        assertThat(seenTenants).containsExactly("T1", "T2");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void adminSeedIsSkippedWithoutBothEmailAndPassword() {
        for (String[] creds : new String[][]{{null, "pw"}, {" ", "pw"}, {"root@example.test", null}, {"root@example.test", " "}}) {
            new PlatformAdminSeedRunner(platformAdminRepository, passwordEncoder, creds[0], creds[1]).run(noArgs);
        }
        verifyNoInteractions(platformAdminRepository, passwordEncoder);
    }

    @Test
    void adminSeedLeavesAnExistingAdminAlone() {
        when(platformAdminRepository.findByEmailIgnoreCase("root@example.test")).thenReturn(Optional.of(new PlatformAdmin()));

        new PlatformAdminSeedRunner(platformAdminRepository, passwordEncoder, "root@example.test", "pw").run(noArgs);

        verify(platformAdminRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void adminSeedCreatesAnActiveAdminWithAnEncodedPassword() {
        when(platformAdminRepository.findByEmailIgnoreCase("root@example.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pw")).thenReturn("encoded");

        new PlatformAdminSeedRunner(platformAdminRepository, passwordEncoder, "root@example.test", "pw").run(noArgs);

        ArgumentCaptor<PlatformAdmin> saved = ArgumentCaptor.forClass(PlatformAdmin.class);
        verify(platformAdminRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotBlank();
        assertThat(saved.getValue().getName()).isEqualTo("Platform Admin");
        assertThat(saved.getValue().getEmail()).isEqualTo("root@example.test");
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded");
        assertThat(saved.getValue().getIsActive()).isTrue();
    }
}
