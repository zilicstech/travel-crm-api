package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The current agency's base currency: its own code, read once per tenant, else INR. */
@ExtendWith(MockitoExtension.class)
class BaseCurrencyTest {

    @Mock private TenantRepository tenantRepository;
    @InjectMocks private BaseCurrency baseCurrency;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void withNoTenantItIsInr() {
        assertThat(baseCurrency.code()).isEqualTo("INR");
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void theTenantsOwnCodeIsReadOnceAndCached() {
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").baseCurrencyCode("USD").build()));
        TenantContext.setTenantId("T1");

        assertThat(baseCurrency.code()).isEqualTo("USD");
        assertThat(baseCurrency.code()).isEqualTo("USD");
        verify(tenantRepository, times(1)).findById("T1");
    }

    @Test
    void aMissingTenantOrABlankOrNullCodeFallsBackToInr() {
        when(tenantRepository.findById("T0")).thenReturn(Optional.empty());
        when(tenantRepository.findById("T2")).thenReturn(Optional.of(Tenant.builder().id("T2").baseCurrencyCode(" ").build()));
        when(tenantRepository.findById("T3")).thenReturn(Optional.of(Tenant.builder().id("T3").baseCurrencyCode(null).build()));

        assertThat(baseCurrency.of("T0")).isEqualTo("INR");
        assertThat(baseCurrency.of("T2")).isEqualTo("INR");
        assertThat(baseCurrency.of("T3")).isEqualTo("INR");
    }
}
