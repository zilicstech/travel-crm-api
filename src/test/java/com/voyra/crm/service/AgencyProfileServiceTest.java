package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyProfileUpdateRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The base currency is set once, when the agency is created - every amount in its books is in it. */
@ExtendWith(MockitoExtension.class)
class AgencyProfileServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private FileStorageService fileStorageService;
    @InjectMocks private AgencyProfileService service;

    private final Tenant tenant = Tenant.builder().id("T1").agencyName("Desert Trips").baseCurrencyCode("AED").build();

    @BeforeEach
    void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("T1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
        lenient().when(tenantRepository.findById("T1")).thenReturn(Optional.of(tenant));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theBaseCurrencyCannotBeChanged() {
        AgencyProfileUpdateRequest r = new AgencyProfileUpdateRequest();
        r.setBaseCurrencyCode("INR");

        assertThatThrownBy(() -> service.updateProfile(r)).hasMessageContaining("can't be changed");
        assertThat(tenant.getBaseCurrencyCode()).isEqualTo("AED");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void sendingTheSameBaseCurrencyBackIsFine() {
        AgencyProfileUpdateRequest r = new AgencyProfileUpdateRequest();
        r.setBaseCurrencyCode("aed");
        r.setAddress("Deira, Dubai");

        service.updateProfile(r);

        assertThat(tenant.getAddress()).isEqualTo("Deira, Dubai");
        assertThat(tenant.getBaseCurrencyCode()).isEqualTo("AED");
    }

    // ---- logo

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    @Test
    void aLogoUploadStoresTheFileSetsTheKeyAndDeletesTheOldLogo() {
        tenant.setLogoKey("tenant_t1/logo/T1/old-logo.png");
        MockMultipartFile png = file("logo.png", PNG);
        when(fileStorageService.store("T1", "logo", "T1", png)).thenReturn("tenant_t1/logo/T1/new-logo.png");

        var r = service.uploadLogo(png);

        assertThat(tenant.getLogoKey()).isEqualTo("tenant_t1/logo/T1/new-logo.png");
        assertThat(r.isHasLogo()).isTrue();
        verify(fileStorageService).delete("tenant_t1/logo/T1/old-logo.png");
        verify(tenantRepository).save(tenant);
    }

    /** The onboarding logo is the application's KYC file, kept as evidence: replacing it never deletes it. */
    @Test
    void replacingTheOnboardingLogoKeepsTheKycFile() {
        tenant.setLogoKey("tenant_platform/kyc/APP1/abc-logo.png");
        when(fileStorageService.store(eq("T1"), eq("logo"), eq("T1"), any())).thenReturn("tenant_t1/logo/T1/new-logo.png");
        service.uploadLogo(file("logo.png", PNG));
        verify(fileStorageService, never()).delete(any());
    }

    @Test
    void aLogoMustBeARealSmallImage() {
        byte[] text = "not an image at all".getBytes();
        for (MockMultipartFile bad : List.of(file("logo.png", text), file("logo.pdf", "%PDF-1.4".getBytes()),
                file("logo.png", java.util.Arrays.copyOf(PNG, 1024 * 1024 + 1)), file("logo.png", new byte[0]))) {
            assertThatThrownBy(() -> service.uploadLogo(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PNG, JPG or WEBP");
        }
        verify(fileStorageService, never()).store(any(), any(), any(), any());
    }

    @Test
    void removingTheLogoClearsTheKeyAndDeletesTheFile() {
        tenant.setLogoKey("tenant_t1/logo/T1/old-logo.webp");
        var r = service.removeLogo();
        assertThat(tenant.getLogoKey()).isNull();
        assertThat(r.isHasLogo()).isFalse();
        verify(fileStorageService).delete("tenant_t1/logo/T1/old-logo.webp");
    }

    @Test
    void anyAgencyUserReadsTheLogoWithItsImageTypeAndNoneIs404() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
        assertThatThrownBy(() -> service.logo()).isInstanceOf(com.voyra.crm.exception.NotFoundException.class);

        tenant.setLogoKey("tenant_t1/logo/T1/abc-Logo.JPG");
        org.springframework.core.io.Resource img = new org.springframework.core.io.ByteArrayResource(PNG);
        when(fileStorageService.retrieve("tenant_t1/logo/T1/abc-Logo.JPG")).thenReturn(img);
        AgencyProfileService.Logo logo = service.logo();
        assertThat(logo.contentType()).isEqualTo("image/jpeg");
        assertThat(logo.resource()).isSameAs(img);
    }
}
