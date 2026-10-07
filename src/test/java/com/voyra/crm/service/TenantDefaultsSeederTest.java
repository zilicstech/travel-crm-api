package com.voyra.crm.service;

import com.voyra.crm.config.AgencySettingDefaults;
import com.voyra.crm.config.VendorDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class TenantDefaultsSeederTest {

    @Mock
    private AgencySettingDefaults agencySettingSeedRunner;
    @Mock
    private VendorDefaults vendorSeedRunner;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @InjectMocks
    private TenantDefaultsSeeder seeder;

    @Test
    void locksTheTenantThenSeedsSettingsThenVendors() {
        seeder.seedCurrentTenant();

        InOrder order = inOrder(jdbcTemplate, agencySettingSeedRunner, vendorSeedRunner);
        // The per-tenant lock must be taken before the count-then-insert, or two booting
        // instances race on the same empty tenant.
        order.verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ResultSetExtractor.class),
                org.mockito.ArgumentMatchers.<Object>any());
        order.verify(agencySettingSeedRunner).seedCurrentTenant();
        order.verify(vendorSeedRunner).seedCurrentTenant();
    }
}
