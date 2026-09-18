package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds a brand-new tenant's vendor table with the same demo names the retired
 * {@code AgencySettingSeedRunner.suppliersFor(type)} used to seed. For an existing tenant, V11's
 * copy-across from {@code agency_setting} already populated this table, so this no-ops. Runs
 * after the agency setting seed (order 150), before demo business data (order 200).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(160)
public class VendorSeedRunner implements ApplicationRunner {

    private final TenantRepository tenantRepository;
    private final VendorRepository vendorRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = tenantRepository.findAll();
        int seeded = 0;
        for (Tenant tenant : tenants) {
            TenantContext.setTenantId(tenant.getId());
            try {
                if (vendorRepository.count() == 0) {
                    seedDefaults();
                    seeded++;
                }
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Vendor defaults seeded for {} of {} tenant(s)", seeded, tenants.size());
    }

    private void seedDefaults() {
        int order = 0;
        for (Default d : DEFAULTS) {
            Vendor vendor = Vendor.builder()
                    .id(UniqueIdResolver.resolve(vendorRepository::existsById))
                    .name(d.name())
                    .serviceTypes(d.serviceTypes())
                    .isActive(true)
                    .sortOrder(order++)
                    .createdAt(LocalDateTime.now())
                    .build();
            vendorRepository.save(vendor);
        }
    }

    private record Default(String name, List<ServiceType> serviceTypes) {
    }

    private static final List<Default> DEFAULTS = List.of(
            new Default("IndiGo", List.of(ServiceType.FLIGHT)),
            new Default("Air India", List.of(ServiceType.FLIGHT)),
            new Default("Vistara", List.of(ServiceType.FLIGHT)),
            new Default("Tripjack", List.of(ServiceType.FLIGHT, ServiceType.HOTEL)),
            new Default("Booking.com", List.of(ServiceType.HOTEL)),
            new Default("Agoda", List.of(ServiceType.HOTEL)),
            new Default("VFS Global", List.of(ServiceType.VISA)),
            new Default("BLS International", List.of(ServiceType.VISA)),
            new Default("Local Cab Operator", List.of(ServiceType.TRANSFER)),
            new Default("Savaari", List.of(ServiceType.TRANSFER)));
}
