package com.voyra.crm.scheduler;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Another instance's status change, or a newly approved agency, reaches this instance's cache without a restart. */
@ExtendWith(MockitoExtension.class)
class TenantCacheRefreshJobTest {

    @Mock private TenantRepository tenantRepository;
    @InjectMocks private TenantCacheRefreshJob job;

    @AfterEach
    void clean() {
        TenantCache.remove("TREF1");
        TenantCache.remove("TREF2");
    }

    @Test
    void aStatusChangedInTheDatabaseIsPickedUpAndANewTenantIsAddedKeepingEntitlements() {
        Entitlements ent = new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, Set.of(), 5, false, null);
        TenantCache.put("TREF1", "A", TenantStatus.ACTIVE);
        TenantCache.putEntitlements("TREF1", ent);
        when(tenantRepository.findAll()).thenReturn(List.of(
                Tenant.builder().id("TREF1").agencyName("A").status(TenantStatus.SUSPENDED).build(),
                Tenant.builder().id("TREF2").agencyName("B").status(TenantStatus.ACTIVE).build()));

        job.run();

        assertThat(TenantCache.status("TREF1")).contains(TenantStatus.SUSPENDED);
        assertThat(TenantCache.entitlements("TREF1")).contains(ent);
        assertThat(TenantCache.isValidTenant("TREF2")).isTrue();
    }

    @Test
    void aDeactivationElsewhereInvalidatesTheTenantHere() {
        TenantCache.put("TREF1", "A", TenantStatus.ACTIVE);
        when(tenantRepository.findAll()).thenReturn(List.of(
                Tenant.builder().id("TREF1").agencyName("A").status(TenantStatus.DEACTIVATED).build()));

        job.run();

        assertThat(TenantCache.isValidTenant("TREF1")).isFalse();
    }
}
