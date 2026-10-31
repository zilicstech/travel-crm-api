package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyProfileServiceCoverageTest {

    @Mock TenantRepository tenantRepository;
    @Mock FileStorageService fileStorageService;
    @InjectMocks AgencyProfileService service;

    private final Tenant tenant = Tenant.builder().id("T1").agencyName("Desert Trips").ownerName("Dana")
            .ownerEmail("owner@example.test").baseCurrencyCode("INR").stateCode("27").build();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String userId, String tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", UserType.AGENCY_OWNER, tenantId), null, List.of()));
    }

    private void owner() {
        signInAs("T1", "T1");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(tenant));
    }

    @Test
    void theProfileReadsTheOwnersAgency() {
        owner();

        AgencyProfileResponse res = service.getProfile();

        assertThat(res.getAgencyName()).isEqualTo("Desert Trips");
        assertThat(res.getOwnerName()).isEqualTo("Dana");
        assertThat(res.getOwnerEmail()).isEqualTo("owner@example.test");
        assertThat(res.isHasLogo()).isFalse();
    }

    @Test
    void aMissingAgencyIsAnError() {
        signInAs("T9", "T9");
        when(tenantRepository.findById("T9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile()).hasMessage("Agency not found: T9");
    }

    @Test
    void everyGivenFieldIsSavedTidied() {
        owner();
        AgencyProfileUpdateRequest r = new AgencyProfileUpdateRequest();
        r.setGstNumber(" 27aaaaa0000a1z5 ");
        r.setStateCode("27");
        r.setAddress("Fort, Mumbai");
        r.setCurrency("INR");
        r.setDefaultCommission(new BigDecimal("5"));
        r.setBaseCurrencyCode(" ");
        r.setDefaultSacCode("998555");
        r.setInvoiceTerms("Net 7");
        r.setLegalName("Desert Trips Pvt Ltd");
        r.setBankAccountName("Desert Trips");
        r.setBankAccountNumber("000111");
        r.setBankIfscCode(" hdfc0000001 ");
        r.setBankBranch("Fort");
        r.setContactPhone(" ");
        r.setContactEmail(" desk@example.test ");
        r.setWebsite("https://example.test");

        AgencyProfileResponse res = service.updateProfile(r);

        assertThat(res.getGstNumber()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(res.getAddress()).isEqualTo("Fort, Mumbai");
        assertThat(res.getCurrency()).isEqualTo("INR");
        assertThat(res.getDefaultCommission()).isEqualByComparingTo("5");
        assertThat(res.getDefaultSacCode()).isEqualTo("998555");
        assertThat(res.getInvoiceTerms()).isEqualTo("Net 7");
        assertThat(res.getLegalName()).isEqualTo("Desert Trips Pvt Ltd");
        assertThat(res.getBankAccountName()).isEqualTo("Desert Trips");
        assertThat(res.getBankAccountNumber()).isEqualTo("000111");
        assertThat(res.getBankIfscCode()).isEqualTo("HDFC0000001");
        assertThat(res.getBankBranch()).isEqualTo("Fort");
        assertThat(res.getContactPhone()).isNull();
        assertThat(res.getContactEmail()).isEqualTo("desk@example.test");
        assertThat(res.getWebsite()).isEqualTo("https://example.test");
        verify(tenantRepository).save(tenant);
    }

    @Test
    void aStateCodeAloneIsCheckedAgainstTheStoredGstin() {
        owner();
        tenant.setGstNumber("27AAAAA0000A1Z5");
        AgencyProfileUpdateRequest r = new AgencyProfileUpdateRequest();
        r.setStateCode("29");

        assertThatThrownBy(() -> service.updateProfile(r)).hasMessageContaining("registered in state 27, but the GST state is 29");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void anEmptyUpdateChangesNothing() {
        owner();

        service.updateProfile(new AgencyProfileUpdateRequest());

        assertThat(tenant.getStateCode()).isEqualTo("27");
        verify(tenantRepository).save(tenant);
    }

    @Test
    void removingNoLogoDeletesNothingAndAFailedDeleteIsNotAnError() {
        owner();

        service.removeLogo();
        verify(fileStorageService, never()).delete(any());

        tenant.setLogoKey("tenant_t1/logo/T1/old.png");
        doThrow(new IllegalStateException("gone")).when(fileStorageService).delete("tenant_t1/logo/T1/old.png");
        assertThat(service.removeLogo().getLogoKey()).isNull();
    }

    @Test
    void aBlankLogoKeyReadsAsNoLogo() {
        owner();
        tenant.setLogoKey(" ");

        assertThat(service.getProfile().isHasLogo()).isFalse();
        assertThatThrownBy(() -> service.logo()).isInstanceOf(NotFoundException.class).hasMessage("This agency has no logo");
    }

    @Test
    void aUserWithNoAgencyOrAnUnknownAgencyHasNoLogo() {
        signInAs("SA1", null);
        assertThatThrownBy(() -> service.logo()).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(tenantRepository);

        signInAs("A1", "T9");
        when(tenantRepository.findById("T9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logo()).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aLogoOfUnknownTypeIsServedAsBinary() {
        signInAs("A1", "T1");
        tenant.setLogoKey("tenant_t1/kyc/APP1/logo.bin");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(tenant));
        when(fileStorageService.retrieve("tenant_t1/kyc/APP1/logo.bin")).thenReturn(new ByteArrayResource(new byte[]{1}));

        assertThat(service.logo().contentType()).isEqualTo("application/octet-stream");
    }
}
