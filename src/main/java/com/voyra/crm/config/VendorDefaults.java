package com.voyra.crm.config;

import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The default vendor rows for a brand-new tenant (the demo names the retired agency_setting
 * supplier seed used). An existing tenant got its vendors from V11's copy-across, so this no-ops
 * there. Only ever invoked through {@link com.voyra.crm.service.TenantDefaultsSeeder}, which
 * supplies the per-tenant transaction and lock; the count-then-insert below is not race-safe on
 * its own (two instances booting together both saw 0 and both inserted - uq_vendor_name_active).
 */
@Component
@RequiredArgsConstructor
public class VendorDefaults {

    private final VendorRepository vendorRepository;

    /** Seeds the current tenant only if it has no vendors yet. Returns whether anything was seeded. */
    public boolean seedCurrentTenant() {
        if (vendorRepository.count() != 0) {
            return false;
        }
        seedDefaults();
        return true;
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
