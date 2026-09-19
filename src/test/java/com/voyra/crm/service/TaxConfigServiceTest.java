package com.voyra.crm.service;

import com.voyra.crm.dto.TaxRateConfigCreateRequest;
import com.voyra.crm.dto.TaxRateConfigResponse;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.TaxRateConfigRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A rate is never updated in place - replace() must close the old row (effectiveTo, isActive
 * false) and insert a new one, never mutate the old row's rate/label in place.
 */
@ExtendWith(MockitoExtension.class)
class TaxConfigServiceTest {

    @Mock
    private TaxRateConfigRepository taxRateConfigRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private TaxConfigService taxConfigService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private TaxRateConfigCreateRequest request(String rate, LocalDate effectiveFrom) {
        TaxRateConfigCreateRequest r = new TaxRateConfigCreateRequest();
        r.setTaxKind(TaxKind.GST);
        r.setLabel("Domestic package");
        r.setSacCode("9985");
        r.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        r.setRatePercent(new BigDecimal(rate));
        r.setEffectiveFrom(effectiveFrom);
        r.setIsDefault(true);
        return r;
    }

    @Test
    void createPersistsANewRowAndAuditsIt() {
        when(taxRateConfigRepository.existsById(any())).thenReturn(false);

        TaxRateConfigResponse response = taxConfigService.create(request("5.000", LocalDate.now()));

        assertThat(response.getRatePercent()).isEqualByComparingTo("5.000");
        assertThat(response.getIsActive()).isTrue();
        verify(auditService).recordCreate(any(), any(), any());
    }

    @Test
    void replaceClosesTheOldRowRatherThanMutatingItsRate() {
        TaxRateConfig existing = TaxRateConfig.builder()
                .id("G1").taxKind(TaxKind.GST).label("Domestic package").supplyNature(SupplyNature.DOMESTIC_PACKAGE)
                .ratePercent(new BigDecimal("5.000")).taxablePercent(new BigDecimal("100.000"))
                .effectiveFrom(LocalDate.now().minusYears(1)).isDefault(true).isActive(true)
                .build();
        when(taxRateConfigRepository.findById("G1")).thenReturn(Optional.of(existing));
        when(taxRateConfigRepository.existsById(any())).thenReturn(false);

        LocalDate newEffectiveFrom = LocalDate.now().plusDays(1);
        TaxRateConfigResponse response = taxConfigService.replace("G1", request("8.000", newEffectiveFrom));

        ArgumentCaptor<TaxRateConfig> savedCaptor = ArgumentCaptor.forClass(TaxRateConfig.class);
        verify(taxRateConfigRepository, times(2)).save(savedCaptor.capture());
        TaxRateConfig closedOld = savedCaptor.getAllValues().get(0);
        TaxRateConfig inserted = savedCaptor.getAllValues().get(1);

        assertThat(closedOld.getId()).isEqualTo("G1");
        assertThat(closedOld.getRatePercent()).isEqualByComparingTo("5.000"); // untouched
        assertThat(closedOld.getIsActive()).isFalse();
        assertThat(closedOld.getEffectiveTo()).isEqualTo(newEffectiveFrom.minusDays(1));

        assertThat(inserted.getId()).isNotEqualTo("G1");
        assertThat(inserted.getRatePercent()).isEqualByComparingTo("8.000");
        assertThat(response.getRatePercent()).isEqualByComparingTo("8.000");
    }

    @Test
    void sameDayReplaceNeverProducesAnInvertedEffectiveWindow() {
        LocalDate today = LocalDate.now();
        TaxRateConfig existing = TaxRateConfig.builder()
                .id("G3").taxKind(TaxKind.GST).label("Domestic package").supplyNature(SupplyNature.DOMESTIC_PACKAGE)
                .ratePercent(new BigDecimal("5.000")).taxablePercent(new BigDecimal("100.000"))
                .effectiveFrom(today).isDefault(true).isActive(true)
                .build();
        when(taxRateConfigRepository.findById("G3")).thenReturn(Optional.of(existing));
        when(taxRateConfigRepository.existsById(any())).thenReturn(false);

        taxConfigService.replace("G3", request("6.000", today));

        ArgumentCaptor<TaxRateConfig> savedCaptor = ArgumentCaptor.forClass(TaxRateConfig.class);
        verify(taxRateConfigRepository, times(2)).save(savedCaptor.capture());
        TaxRateConfig closedOld = savedCaptor.getAllValues().get(0);

        assertThat(closedOld.getEffectiveTo()).isAfterOrEqualTo(closedOld.getEffectiveFrom());
    }

    @Test
    void updateStatusTogglesWithoutVersioning() {
        TaxRateConfig existing = TaxRateConfig.builder()
                .id("G2").taxKind(TaxKind.GST).label("Domestic package").supplyNature(SupplyNature.DOMESTIC_PACKAGE)
                .ratePercent(new BigDecimal("5.000")).taxablePercent(new BigDecimal("100.000"))
                .effectiveFrom(LocalDate.now()).isDefault(true).isActive(true)
                .build();
        when(taxRateConfigRepository.findById("G2")).thenReturn(Optional.of(existing));

        TaxRateConfigResponse response = taxConfigService.updateStatus("G2", false);

        assertThat(response.getIsActive()).isFalse();
        verify(taxRateConfigRepository, times(1)).save(any());
        verify(auditService, never()).recordCreate(any(), any(), any());
    }
}
