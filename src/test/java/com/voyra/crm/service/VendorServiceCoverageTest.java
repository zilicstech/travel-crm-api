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
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VendorServiceCoverageTest {

    @Mock VendorRepository vendorRepository;
    @Mock BookingRepository bookingRepository;
    @Mock LeadProposalRepository leadProposalRepository;
    @Mock SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock AuditService auditService;
    @InjectMocks VendorService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "user", type, "T1"), null, List.of()));
    }

    private Vendor fullVendor() {
        return Vendor.builder().id("V1").name("Agoda").serviceTypes(List.of(ServiceType.HOTEL))
                .panNumber("ABCDE1234F").creditLimitInr(new BigDecimal("50000")).lowBalanceThresholdInr(new BigDecimal("1000"))
                .tdsSection("194C").tdsRatePercent(new BigDecimal("2")).bankAccountName("Agoda Co")
                .bankAccountNumber("000111").bankIfsc("HDFC0000001").isActive(true).sortOrder(3).build();
    }

    @Test
    void createTrimsTheNameUppercasesTheIfscAndAppendsAtTheEnd() {
        signInAs(UserType.AGENCY_OWNER);
        when(vendorRepository.existsByNameIgnoreCase("Agoda")).thenReturn(false);
        when(vendorRepository.existsById(anyString())).thenReturn(false);
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(
                Vendor.builder().sortOrder(2).build(), Vendor.builder().sortOrder(7).build()));
        VendorCreateRequest req = new VendorCreateRequest();
        req.setName("  Agoda ");
        req.setServiceTypes(List.of(ServiceType.HOTEL));
        req.setIsPrepaid(true);
        req.setBankIfsc("hdfc0000001");
        req.setPanNumber("ABCDE1234F");

        VendorResponse res = service.create(req);

        ArgumentCaptor<Vendor> saved = ArgumentCaptor.forClass(Vendor.class);
        verify(vendorRepository).save(saved.capture());
        Vendor v = saved.getValue();
        assertThat(v.getName()).isEqualTo("Agoda");
        assertThat(v.getSortOrder()).isEqualTo(8);
        assertThat(v.getIsPrepaid()).isTrue();
        assertThat(v.getIsActive()).isTrue();
        assertThat(v.getBankIfsc()).isEqualTo("HDFC0000001");
        assertThat(v.getCreatedBy()).isEqualTo("U1");
        assertThat(res.getPanNumber()).isEqualTo("ABCDE1234F");
        verify(auditService).recordCreate(AuditEntityType.VENDOR, v.getId(), "Agoda");
    }

    @Test
    void createDefaultsPrepaidToFalseAndKeepsAMissingIfscEmpty() {
        signInAs(UserType.AGENCY_OWNER);
        when(vendorRepository.existsByNameIgnoreCase("Agoda")).thenReturn(false);
        when(vendorRepository.existsById(anyString())).thenReturn(false);
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of());
        VendorCreateRequest req = new VendorCreateRequest();
        req.setName("Agoda");
        req.setServiceTypes(List.of(ServiceType.HOTEL));

        service.create(req);

        ArgumentCaptor<Vendor> saved = ArgumentCaptor.forClass(Vendor.class);
        verify(vendorRepository).save(saved.capture());
        assertThat(saved.getValue().getIsPrepaid()).isFalse();
        assertThat(saved.getValue().getBankIfsc()).isNull();
        assertThat(saved.getValue().getSortOrder()).isZero();
    }

    @Test
    void createRefusesADuplicateName() {
        when(vendorRepository.existsByNameIgnoreCase("Agoda")).thenReturn(true);
        VendorCreateRequest req = new VendorCreateRequest();
        req.setName("Agoda");

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(IllegalStateException.class)
                .hasMessage("\"Agoda\" already exists");
        verify(vendorRepository, never()).save(any());
    }

    @Test
    void listByServiceTypeUsesTheActiveQueryOrFiltersAllVendors() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor hotel = fullVendor();
        Vendor flight = Vendor.builder().id("V2").name("Indigo").serviceTypes(List.of(ServiceType.FLIGHT)).build();
        when(vendorRepository.findActiveByServiceType("HOTEL")).thenReturn(List.of(hotel));
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(hotel, flight));

        assertThat(service.list(ServiceType.HOTEL, true)).extracting(VendorResponse::getId).containsExactly("V1");
        assertThat(service.list(ServiceType.FLIGHT, false)).extracting(VendorResponse::getId).containsExactly("V2");
    }

    @Test
    void listActiveOnlyWithoutType() {
        signInAs(UserType.ACCOUNTANT);
        when(vendorRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc()).thenReturn(List.of(fullVendor()));

        assertThat(service.list(null, true)).singleElement()
                .satisfies(r -> assertThat(r.getBankAccountNumber()).isEqualTo("000111"));
    }

    @Test
    void anAgentDoesNotSeeTheFinancialProfile() {
        signInAs(UserType.AGENT);
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(fullVendor()));

        VendorResponse res = service.get("V1");

        assertThat(res.getName()).isEqualTo("Agoda");
        assertThat(res.getPanNumber()).isNull();
        assertThat(res.getCreditLimitInr()).isNull();
        assertThat(res.getLowBalanceThresholdInr()).isNull();
        assertThat(res.getTdsSection()).isNull();
        assertThat(res.getTdsRatePercent()).isNull();
        assertThat(res.getBankAccountName()).isNull();
        assertThat(res.getBankAccountNumber()).isNull();
        assertThat(res.getBankIfsc()).isNull();
    }

    @Test
    void aSuperAdminSeesTheFinancialProfile() {
        signInAs(UserType.SUPER_ADMIN);
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(fullVendor()));

        VendorResponse res = service.get("V1");

        assertThat(res.getPanNumber()).isEqualTo("ABCDE1234F");
        assertThat(res.getCreditLimitInr()).isEqualByComparingTo("50000");
        assertThat(res.getLowBalanceThresholdInr()).isEqualByComparingTo("1000");
        assertThat(res.getTdsSection()).isEqualTo("194C");
        assertThat(res.getTdsRatePercent()).isEqualByComparingTo("2");
        assertThat(res.getBankAccountName()).isEqualTo("Agoda Co");
        assertThat(res.getBankIfsc()).isEqualTo("HDFC0000001");
    }

    @Test
    void getRefusesAnUnknownVendor() {
        when(vendorRepository.findById("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("NOPE")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vendor not found: NOPE");
    }

    @Test
    void updateSetsEveryGivenFieldAndResyncsSnapshotsOnRename() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));
        when(vendorRepository.existsByNameIgnoreCaseAndIdNot("Agoda Asia", "V1")).thenReturn(false);
        VendorUpdateRequest req = new VendorUpdateRequest();
        req.setName(" Agoda Asia ");
        req.setServiceTypes(List.of(ServiceType.HOTEL, ServiceType.VISA));
        req.setContactPerson("Ravi");
        req.setPhone("+91-00000-00000");
        req.setEmail("supplier@example.test");
        req.setAddress("Somewhere 1");
        req.setGstNumber("29AAAAA0000A1Z5");
        req.setNotes("n");
        req.setDefaultRateNote("net rates");
        req.setSortOrder(9);
        req.setStateCode("29");
        req.setPanNumber("ZZZZZ9999Z");
        req.setIsPrepaid(true);
        req.setPaymentTermsDays(30);
        req.setCreditLimitInr(new BigDecimal("75000"));
        req.setLowBalanceThresholdInr(new BigDecimal("500"));
        req.setTdsSection("194J");
        req.setTdsRatePercent(new BigDecimal("10"));
        req.setBankAccountName("Agoda Asia Pte");
        req.setBankAccountNumber("999");
        req.setBankIfsc("icic0000002");

        VendorResponse res = service.update("V1", req);

        assertThat(v.getName()).isEqualTo("Agoda Asia");
        assertThat(v.getServiceTypes()).containsExactly(ServiceType.HOTEL, ServiceType.VISA);
        assertThat(v.getContactPerson()).isEqualTo("Ravi");
        assertThat(v.getPhone()).isEqualTo("+91-00000-00000");
        assertThat(v.getEmail()).isEqualTo("supplier@example.test");
        assertThat(v.getAddress()).isEqualTo("Somewhere 1");
        assertThat(v.getGstNumber()).isEqualTo("29AAAAA0000A1Z5");
        assertThat(v.getNotes()).isEqualTo("n");
        assertThat(v.getDefaultRateNote()).isEqualTo("net rates");
        assertThat(v.getSortOrder()).isEqualTo(9);
        assertThat(v.getStateCode()).isEqualTo("29");
        assertThat(v.getPanNumber()).isEqualTo("ZZZZZ9999Z");
        assertThat(v.getIsPrepaid()).isTrue();
        assertThat(v.getPaymentTermsDays()).isEqualTo(30);
        assertThat(v.getCreditLimitInr()).isEqualByComparingTo("75000");
        assertThat(v.getLowBalanceThresholdInr()).isEqualByComparingTo("500");
        assertThat(v.getTdsSection()).isEqualTo("194J");
        assertThat(v.getTdsRatePercent()).isEqualByComparingTo("10");
        assertThat(v.getBankAccountName()).isEqualTo("Agoda Asia Pte");
        assertThat(v.getBankAccountNumber()).isEqualTo("999");
        assertThat(v.getBankIfsc()).isEqualTo("ICIC0000002");
        assertThat(v.getUpdatedBy()).isEqualTo("U1");
        assertThat(v.getUpdatedAt()).isNotNull();
        assertThat(res.getName()).isEqualTo("Agoda Asia");
        verify(bookingRepository).updateSupplierName("Agoda", "Agoda Asia");
        verify(leadProposalRepository).updateSupplierName("Agoda", "Agoda Asia");
        verify(supplierInvoiceRepository).updateSupplierName("Agoda", "Agoda Asia");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(eq(AuditEntityType.VENDOR), eq("V1"), eq("Agoda Asia"), changes.capture());
        assertThat(changes.getValue()).hasSizeGreaterThan(15);
    }

    @Test
    void updateWithNothingChangesNothingAndDoesNotResync() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));

        service.update("V1", new VendorUpdateRequest());

        assertThat(v.getName()).isEqualTo("Agoda");
        verify(vendorRepository).save(v);
        verify(auditService).recordUpdate(AuditEntityType.VENDOR, "V1", "Agoda", List.of());
        verifyNoInteractions(bookingRepository, leadProposalRepository, supplierInvoiceRepository);
    }

    @Test
    void aCaseOnlyRenameDoesNotResync() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));
        when(vendorRepository.existsByNameIgnoreCaseAndIdNot("AGODA", "V1")).thenReturn(false);
        VendorUpdateRequest req = new VendorUpdateRequest();
        req.setName("AGODA");

        service.update("V1", req);

        assertThat(v.getName()).isEqualTo("AGODA");
        verifyNoInteractions(bookingRepository, leadProposalRepository, supplierInvoiceRepository);
    }

    @Test
    void updateRefusesANameTakenByAnotherVendor() {
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));
        when(vendorRepository.existsByNameIgnoreCaseAndIdNot("Indigo", "V1")).thenReturn(true);
        VendorUpdateRequest req = new VendorUpdateRequest();
        req.setName("Indigo");

        assertThatThrownBy(() -> service.update("V1", req)).isInstanceOf(IllegalStateException.class)
                .hasMessage("\"Indigo\" already exists");
        verify(vendorRepository, never()).save(any());
    }

    @Test
    void theSystemVendorAcceptsAnEditWithoutNameOrServices() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor voyra = Vendor.builder().id("V-VOYRA").name("Voyra Platform")
                .serviceTypes(List.of(ServiceType.FLIGHT)).isActive(true).isSystem(true).build();
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));
        VendorUpdateRequest req = new VendorUpdateRequest();
        req.setPanNumber("AAACV1234A");

        service.update("V-VOYRA", req);

        assertThat(voyra.getPanNumber()).isEqualTo("AAACV1234A");
        verify(vendorRepository).save(voyra);
    }

    @Test
    void theSystemVendorAcceptsItsOwnNameWithoutServices() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor voyra = Vendor.builder().id("V-VOYRA").name("Voyra Platform")
                .serviceTypes(List.of(ServiceType.FLIGHT)).isActive(true).isSystem(true).build();
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));
        when(vendorRepository.existsByNameIgnoreCaseAndIdNot("Voyra Platform", "V-VOYRA")).thenReturn(false);
        VendorUpdateRequest req = new VendorUpdateRequest();
        req.setName("Voyra Platform");

        service.update("V-VOYRA", req);

        verify(vendorRepository).save(voyra);
    }

    @Test
    void updateStatusDeactivatesAndAudits() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));

        VendorResponse res = service.updateStatus("V1", false);

        assertThat(v.getIsActive()).isFalse();
        assertThat(res.getIsActive()).isFalse();
        verify(vendorRepository).save(v);
        verify(auditService).recordUpdate(eq(AuditEntityType.VENDOR), eq("V1"), eq("Agoda"), anyList());
    }

    @Test
    void deactivateTurnsAnOrdinaryVendorOff() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor v = fullVendor();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(v));

        service.deactivate("V1");

        assertThat(v.getIsActive()).isFalse();
        verify(vendorRepository).save(v);
    }

    @Test
    void theSystemVendorMayBeSetToTheStatusItAlreadyHas() {
        signInAs(UserType.AGENCY_OWNER);
        Vendor voyra = Vendor.builder().id("V-VOYRA").name("Voyra Platform")
                .serviceTypes(List.of(ServiceType.FLIGHT)).isActive(true).isSystem(true).build();
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));

        assertThat(service.updateStatus("V-VOYRA", true).getIsActive()).isTrue();
        verify(vendorRepository).save(voyra);
    }

    @Test
    void legacySettingsEmitOneRowPerVendorAndServiceType() {
        Vendor both = Vendor.builder().id("V1").name("MakeMyTrip").isActive(true).sortOrder(1)
                .serviceTypes(List.of(ServiceType.FLIGHT, ServiceType.HOTEL)).build();
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(both));

        List<AgencySettingResponse> all = service.listAsLegacySettings(null);
        List<AgencySettingResponse> hotels = service.listAsLegacySettings(ServiceType.HOTEL);

        assertThat(all).extracting(AgencySettingResponse::getServiceType).containsExactly(ServiceType.FLIGHT, ServiceType.HOTEL);
        assertThat(hotels).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("V1");
            assertThat(r.getKind()).isEqualTo(AgencySettingKind.SUPPLIER);
            assertThat(r.getName()).isEqualTo("MakeMyTrip");
            assertThat(r.getIsDefault()).isFalse();
            assertThat(r.getSortOrder()).isEqualTo(1);
        });
    }
}
