package com.voyra.crm.service;

import com.voyra.crm.config.AgencySettingSeedRunner;
import com.voyra.crm.config.VendorSeedRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a tenant's default settings and vendors, in a brand-new transaction/connection acquired
 * AFTER the caller sets {@link com.voyra.crm.context.TenantContext} (blueprint §3.5). Must live on
 * its own bean - a self-invoked REQUIRES_NEW method bypasses the Spring proxy and silently runs
 * in the caller's existing transaction/schema. Callers own the try {set} finally {restore/clear}.
 *
 * The same seeding runs at startup for every tenant (the two runners), which is why an agency
 * created while the app was already running used to have none of it until the next restart.
 */
@Service
@RequiredArgsConstructor
public class TenantDefaultsSeeder {

    private final AgencySettingSeedRunner agencySettingSeedRunner;
    private final VendorSeedRunner vendorSeedRunner;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seedCurrentTenant() {
        agencySettingSeedRunner.seedCurrentTenant();
        vendorSeedRunner.seedCurrentTenant();
    }
}
