package com.voyra.crm.config;

import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgencySettingRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The default agency_setting rows (the values the frontend used to hardcode in
 * lib/agencySettings.ts), so a fresh tenant schema is not empty the first time Settings loads.
 * Idempotent per kind - a kind the agency already has rows for is left alone. Only ever invoked
 * through {@link com.voyra.crm.service.TenantDefaultsSeeder}, which supplies the per-tenant
 * transaction and lock; it is not race-safe on its own.
 */
@Component
@RequiredArgsConstructor
public class AgencySettingDefaults {

    private final AgencySettingRepository agencySettingRepository;

    /** Seeds whichever tenant {@link com.voyra.crm.context.TenantContext} currently points at. */
    public void seedCurrentTenant() {
        seedIfEmpty(AgencySettingKind.TRAVEL_CATEGORY, null, TRAVEL_CATEGORIES);
        seedIfEmpty(AgencySettingKind.DOCUMENT_TYPE, null, DOCUMENT_TYPES);
        seedIfEmpty(AgencySettingKind.LEAD_SOURCE, null, LEAD_SOURCES);
        for (ServiceType type : ServiceType.values()) {
            seedIfEmpty(AgencySettingKind.SERVICE_PREFERENCE, type, preferencesFor(type));
        }
    }

    private void seedIfEmpty(AgencySettingKind kind, ServiceType serviceType, List<Default> defaults) {
        boolean exists = serviceType != null
                ? !agencySettingRepository.findByKindAndServiceTypeOrderBySortOrderAsc(kind, serviceType).isEmpty()
                : agencySettingRepository.existsByKind(kind);
        if (exists) {
            return;
        }
        int order = 0;
        for (Default d : defaults) {
            AgencySetting setting = AgencySetting.builder()
                    .id(UniqueIdResolver.resolve(agencySettingRepository::existsById))
                    .kind(kind).serviceType(serviceType).name(d.name())
                    .isActive(true).isDefault(d.isDefault()).sortOrder(order++)
                    .createdAt(LocalDateTime.now())
                    .build();
            agencySettingRepository.save(setting);
        }
    }

    private record Default(String name, boolean isDefault) {
        static Default of(String name) {
            return new Default(name, false);
        }
        static Default seeded(String name) {
            return new Default(name, true);
        }
    }

    private static final List<Default> TRAVEL_CATEGORIES = List.of(
            Default.seeded("Holiday Package"), Default.seeded("Hotel"), Default.seeded("Flight"),
            Default.seeded("Visa"), Default.of("Cruise Package"), Default.of("MICE / Corporate Travel"),
            Default.of("Group Tour"));

    private static final List<Default> DOCUMENT_TYPES = List.of(
            Default.seeded("Passport"), Default.seeded("Visa"), Default.seeded("ID Card / National ID"),
            Default.of("PAN Card"), Default.of("Aadhaar Card"), Default.of("Driving License"),
            Default.of("Travel Insurance"), Default.of("Vaccination Certificate"));

    private static final List<Default> LEAD_SOURCES = List.of(
            Default.of("Website"), Default.of("WhatsApp"), Default.of("Phone Call"),
            Default.of("Social Media"), Default.of("Walk-in"), Default.of("Referral"),
            Default.of("Corporate Direct"));

    private static List<Default> preferencesFor(ServiceType type) {
        return switch (type) {
            case FLIGHT -> List.of(Default.of("Window Seat"), Default.of("Aisle Seat"),
                    Default.of("Extra Legroom"), Default.of("Veg Meal"), Default.of("Direct Flights Only"),
                    Default.of("Wheelchair Assistance"), Default.of("Extra Baggage"));
            case HOTEL -> List.of(Default.of("3 Star"), Default.of("4 Star"), Default.of("5 Star"),
                    Default.of("Early Check-in"), Default.of("Late Check-out"), Default.of("Sea View"),
                    Default.of("Connecting Rooms"), Default.of("Non-smoking Room"), Default.of("Twin Beds"),
                    Default.of("Double Bed"), Default.of("Suite"), Default.of("Room Only (EP)"),
                    Default.of("Breakfast (CP)"), Default.of("Half Board (MAP)"), Default.of("Full Board (AP)"));
            case VISA -> List.of(Default.of("Tourist Visa"), Default.of("Business Visa"),
                    Default.of("Transit Visa"), Default.of("Student Visa"), Default.of("Work Visa"),
                    Default.of("Single Entry"), Default.of("Multiple Entry"), Default.of("Normal Processing"),
                    Default.of("Express Processing"), Default.of("Courier Passport"),
                    Default.of("Appointment Assistance"), Default.of("Document Pickup"));
            case TRANSFER -> List.of(Default.of("Child Seat"), Default.of("Meet & Greet"),
                    Default.of("Extra Luggage Space"), Default.of("Wheelchair Accessible"));
        };
    }
}
