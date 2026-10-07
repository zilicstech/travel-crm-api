package com.voyra.crm.service;

import com.voyra.crm.config.AgencySettingDefaults;
import com.voyra.crm.config.VendorDefaults;
import com.voyra.crm.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a tenant's default settings and vendors, in a brand-new transaction/connection acquired
 * AFTER the caller sets {@link TenantContext} (blueprint §3.5). Must live on its own bean - a
 * self-invoked REQUIRES_NEW method bypasses the Spring proxy and silently runs in the caller's
 * existing transaction/schema. Callers own the try {set} finally {restore/clear}.
 *
 * The seeding is "count, then insert if empty", so it takes a per-tenant advisory lock first: two
 * app instances booting together (a rolling deploy, or more than one instance) otherwise both see
 * an empty tenant and both insert, and the loser dies on uq_vendor_name_active. The second
 * caller waits, then re-counts inside the lock and finds the work already done. All-or-nothing
 * per tenant, so a failure never leaves half the defaults behind.
 */
@Service
@RequiredArgsConstructor
public class TenantDefaultsSeeder {

    private final AgencySettingDefaults agencySettingDefaults;
    private final VendorDefaults vendorDefaults;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seedCurrentTenant() {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null,
                "voyra.tenant-defaults:" + TenantContext.getTenantId());
        agencySettingDefaults.seedCurrentTenant();
        vendorDefaults.seedCurrentTenant();
    }
}
