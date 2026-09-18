package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySettingResponse;
import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.VendorCreateRequest;
import com.voyra.crm.dto.VendorResponse;
import com.voyra.crm.dto.VendorUpdateRequest;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Supplier master records. Consumers ({@code Booking.supplier}, {@code LeadProposal.supplier},
 * {@code SupplierInvoice.supplierName}) reference a vendor by name, not id (§8.4 - no FK); a
 * rename re-syncs all three snapshots in the same transaction, matching how a client or agent
 * rename already works. There is no hard delete - {@link #deactivate} is the only removal path,
 * because nothing holds a real FK to a vendor and a hard delete would silently orphan every
 * booking, proposal line and supplier invoice referencing it by name.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {

    private static final String[] AUDITED = {
            "name", "serviceTypes", "contactPerson", "phone", "email",
            "address", "gstNumber", "notes", "defaultRateNote", "isActive", "sortOrder"
    };

    private final VendorRepository vendorRepository;
    private final BookingRepository bookingRepository;
    private final LeadProposalRepository leadProposalRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final AuditService auditService;

    @Transactional
    public VendorResponse create(VendorCreateRequest request) {
        String name = request.getName().trim();
        if (vendorRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("\"" + name + "\" already exists");
        }
        Vendor vendor = Vendor.builder()
                .id(UniqueIdResolver.resolve(vendorRepository::existsById))
                .name(name)
                .serviceTypes(List.copyOf(request.getServiceTypes()))
                .contactPerson(request.getContactPerson())
                .phone(request.getPhone())
                .email(request.getEmail())
                .address(request.getAddress())
                .gstNumber(request.getGstNumber())
                .notes(request.getNotes())
                .defaultRateNote(request.getDefaultRateNote())
                .isActive(true)
                .sortOrder(nextSortOrder())
                .createdBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        vendorRepository.save(vendor);
        auditService.recordCreate(AuditEntityType.VENDOR, vendor.getId(), vendor.getName());
        log.info("Vendor created: vendorId={}, name={}", vendor.getId(), vendor.getName());
        return toResponse(vendor);
    }

    @Transactional(readOnly = true)
    public List<VendorResponse> list(ServiceType serviceType, boolean activeOnly) {
        List<Vendor> vendors;
        if (serviceType != null) {
            vendors = activeOnly
                    ? vendorRepository.findActiveByServiceType(serviceType.name())
                    : vendorRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                            .filter(v -> v.getServiceTypes().contains(serviceType))
                            .toList();
        } else {
            vendors = activeOnly
                    ? vendorRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                    : vendorRepository.findAllByOrderBySortOrderAscNameAsc();
        }
        return vendors.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VendorResponse get(String id) {
        return toResponse(findVendor(id));
    }

    @Transactional
    public VendorResponse update(String id, VendorUpdateRequest request) {
        Vendor vendor = findVendor(id);
        Map<String, String> before = AuditSnapshot.of(vendor, AUDITED);
        String oldName = vendor.getName();

        if (request.getName() != null) {
            String candidate = request.getName().trim();
            if (vendorRepository.existsByNameIgnoreCaseAndIdNot(candidate, id)) {
                throw new IllegalStateException("\"" + candidate + "\" already exists");
            }
            vendor.setName(candidate);
        }
        if (request.getServiceTypes() != null) vendor.setServiceTypes(List.copyOf(request.getServiceTypes()));
        if (request.getContactPerson() != null) vendor.setContactPerson(request.getContactPerson());
        if (request.getPhone() != null) vendor.setPhone(request.getPhone());
        if (request.getEmail() != null) vendor.setEmail(request.getEmail());
        if (request.getAddress() != null) vendor.setAddress(request.getAddress());
        if (request.getGstNumber() != null) vendor.setGstNumber(request.getGstNumber());
        if (request.getNotes() != null) vendor.setNotes(request.getNotes());
        if (request.getDefaultRateNote() != null) vendor.setDefaultRateNote(request.getDefaultRateNote());
        if (request.getSortOrder() != null) vendor.setSortOrder(request.getSortOrder());

        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(vendor, AUDITED));
        touch(vendor);
        vendorRepository.save(vendor);

        boolean renamed = !oldName.equalsIgnoreCase(vendor.getName());
        if (renamed) {
            // Same transaction as the rename, exactly as ClientService/AgentService do for their
            // denormalised *_name snapshots. These are live references, not history.
            bookingRepository.updateSupplierName(oldName, vendor.getName());
            leadProposalRepository.updateSupplierName(oldName, vendor.getName());
            supplierInvoiceRepository.updateSupplierName(oldName, vendor.getName());
        }

        auditService.recordUpdate(AuditEntityType.VENDOR, vendor.getId(), vendor.getName(), changes);
        log.info("Vendor updated: vendorId={}, renamed={}, changedFields={}", id, renamed, changes.size());
        return toResponse(vendor);
    }

    @Transactional
    public VendorResponse updateStatus(String id, boolean active) {
        Vendor vendor = findVendor(id);
        Map<String, String> before = AuditSnapshot.of(vendor, AUDITED);
        vendor.setIsActive(active);
        touch(vendor);
        vendorRepository.save(vendor);
        auditService.recordUpdate(AuditEntityType.VENDOR, vendor.getId(), vendor.getName(),
                AuditSnapshot.diff(before, AuditSnapshot.of(vendor, AUDITED)));
        log.info("Vendor status updated: vendorId={}, active={}", id, active);
        return toResponse(vendor);
    }

    /** Deactivates. There is no hard delete - see the class javadoc. */
    @Transactional
    public void deactivate(String id) {
        updateStatus(id, false);
    }

    /**
     * Compatibility shim for GET /api/agency/settings?kind=SUPPLIER, so
     * {@code lib/agencySettings.ts}'s pre-existing read helpers keep working during the
     * migration window. Reproduces the old wire shape: one row per (vendor, serviceType) pair
     * when unfiltered, so a two-type vendor still emits two rows the way it used to as two
     * separate agency_setting rows.
     */
    @Transactional(readOnly = true)
    public List<AgencySettingResponse> listAsLegacySettings(ServiceType serviceType) {
        List<Vendor> vendors = vendorRepository.findAllByOrderBySortOrderAscNameAsc();
        return vendors.stream()
                .flatMap(v -> v.getServiceTypes().stream()
                        .filter(t -> serviceType == null || t == serviceType)
                        .map(t -> AgencySettingResponse.builder()
                                .id(v.getId())
                                .kind(AgencySettingKind.SUPPLIER)
                                .serviceType(t)
                                .name(v.getName())
                                .isActive(v.getIsActive())
                                .isDefault(false)
                                .sortOrder(v.getSortOrder())
                                .build()))
                .toList();
    }

    private Vendor findVendor(String id) {
        return vendorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + id));
    }

    private int nextSortOrder() {
        return vendorRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .mapToInt(Vendor::getSortOrder).max().orElse(-1) + 1;
    }

    private void touch(Vendor vendor) {
        vendor.setUpdatedAt(LocalDateTime.now());
        vendor.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
    }

    private VendorResponse toResponse(Vendor v) {
        return VendorResponse.builder()
                .id(v.getId()).name(v.getName()).serviceTypes(v.getServiceTypes())
                .contactPerson(v.getContactPerson()).phone(v.getPhone()).email(v.getEmail())
                .address(v.getAddress()).gstNumber(v.getGstNumber()).notes(v.getNotes())
                .defaultRateNote(v.getDefaultRateNote()).isActive(v.getIsActive())
                .sortOrder(v.getSortOrder()).createdAt(v.getCreatedAt()).updatedAt(v.getUpdatedAt())
                .build();
    }
}
