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
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The base currency is set once, when the agency is created - every amount in its books is in it. */
@ExtendWith(MockitoExtension.class)
class AgencyProfileServiceTest {

    @Mock private TenantRepository tenantRepository;
    @InjectMocks private AgencyProfileService service;

    private final Tenant tenant = Tenant.builder().id("T1").agencyName("Desert Trips").baseCurrencyCode("AED").build();

    @BeforeEach
    void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("T1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(tenant));
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
}
