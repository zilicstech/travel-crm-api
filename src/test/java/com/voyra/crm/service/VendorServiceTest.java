package com.voyra.crm.service;

import com.voyra.crm.dto.VendorResponse;
import com.voyra.crm.dto.VendorUpdateRequest;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VendorServiceTest {

    @Mock private VendorRepository vendorRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private AuditService auditService;
    @InjectMocks private VendorService service;

    private final Vendor voyra = Vendor.builder().id("V-VOYRA").name("Voyra Platform")
            .serviceTypes(List.of(ServiceType.FLIGHT, ServiceType.HOTEL)).isActive(true).isSystem(true).build();

    @BeforeEach
    void signInAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theSystemVendorCannotBeRenamed() {
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));
        VendorUpdateRequest request = new VendorUpdateRequest();
        request.setName("My Voyra");

        assertThatThrownBy(() -> service.update("V-VOYRA", request))
                .isInstanceOf(BookingConflictException.class).hasFieldOrPropertyWithValue("code", "SYSTEM_VENDOR");
        verify(vendorRepository, never()).save(any());
    }

    @Test
    void theSystemVendorsServicesCannotChange() {
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));
        VendorUpdateRequest request = new VendorUpdateRequest();
        request.setServiceTypes(List.of(ServiceType.FLIGHT));

        assertThatThrownBy(() -> service.update("V-VOYRA", request))
                .isInstanceOf(BookingConflictException.class).hasFieldOrPropertyWithValue("code", "SYSTEM_VENDOR");
    }

    @Test
    void theSystemVendorCannotBeDeactivated() {
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));

        assertThatThrownBy(() -> service.deactivate("V-VOYRA"))
                .isInstanceOf(BookingConflictException.class).hasFieldOrPropertyWithValue("code", "SYSTEM_VENDOR");
        assertThat(voyra.getIsActive()).isTrue();
        verify(vendorRepository, never()).save(any());
    }

    /** The edit form sends the locked fields back unchanged; that, plus a GSTIN, goes through. */
    @Test
    void theSystemVendorsTaxFieldsStayEditable() {
        when(vendorRepository.findById("V-VOYRA")).thenReturn(Optional.of(voyra));
        when(vendorRepository.existsByNameIgnoreCaseAndIdNot("Voyra Platform", "V-VOYRA")).thenReturn(false);
        VendorUpdateRequest request = new VendorUpdateRequest();
        request.setName("Voyra Platform");
        request.setServiceTypes(List.of(ServiceType.HOTEL, ServiceType.FLIGHT));
        request.setGstNumber("07AAACV1234A1Z5");
        request.setStateCode("07");

        VendorResponse response = service.update("V-VOYRA", request);

        assertThat(response.getGstNumber()).isEqualTo("07AAACV1234A1Z5");
        assertThat(response.getStateCode()).isEqualTo("07");
        assertThat(response.getIsSystem()).isTrue();
        verify(vendorRepository).save(voyra);
    }

    @Test
    void theSystemVendorIsListedFirst() {
        Vendor agoda = Vendor.builder().id("V1").name("Agoda").sortOrder(0).build();
        when(vendorRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(agoda, voyra));

        assertThat(service.list(null, false)).extracting(VendorResponse::getId).containsExactly("V-VOYRA", "V1");
    }
}
