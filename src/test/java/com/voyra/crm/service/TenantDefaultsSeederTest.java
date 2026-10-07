package com.voyra.crm.service;

import com.voyra.crm.config.AgencySettingSeedRunner;
import com.voyra.crm.config.VendorSeedRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class TenantDefaultsSeederTest {

    @Mock
    private AgencySettingSeedRunner agencySettingSeedRunner;
    @Mock
    private VendorSeedRunner vendorSeedRunner;
    @InjectMocks
    private TenantDefaultsSeeder seeder;

    @Test
    void seedsSettingsThenVendorsForTheCurrentTenant() {
        seeder.seedCurrentTenant();

        InOrder order = inOrder(agencySettingSeedRunner, vendorSeedRunner);
        order.verify(agencySettingSeedRunner).seedCurrentTenant();
        order.verify(vendorSeedRunner).seedCurrentTenant();
    }
}
