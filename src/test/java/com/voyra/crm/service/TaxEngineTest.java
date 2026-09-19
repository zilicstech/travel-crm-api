package com.voyra.crm.service;

import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.repository.TaxRateConfigRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * TaxEngine is compute-only (writes nothing) - these tests pin the place-of-supply
 * determination and the resulting CGST+SGST vs IGST split, plus TCS on an overseas package.
 */
@ExtendWith(MockitoExtension.class)
class TaxEngineTest {

    @Mock
    private ClientService clientService;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private TaxRateConfigRepository taxRateConfigRepository;

    @InjectMocks
    private TaxEngine taxEngine;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(
                Tenant.builder().id("T1").stateCode("27").build()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private TaxRateConfig gstConfig(SupplyNature nature, String rate) {
        return TaxRateConfig.builder()
                .id("G1").taxKind(TaxKind.GST).label("test")
                .supplyNature(nature).ratePercent(new BigDecimal(rate))
                .taxablePercent(new BigDecimal("100.000"))
                .effectiveFrom(LocalDate.now()).isDefault(true).isActive(true)
                .build();
    }

    @Test
    void sameStateClientSplitsIntoCgstAndSgst() {
        when(clientService.findAccessibleClient("K1")).thenReturn(
                Client.builder().id("K1").name("Jane").stateCode("27").isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.DOMESTIC_PACKAGE))
                .thenReturn(Optional.of(gstConfig(SupplyNature.DOMESTIC_PACKAGE, "5.000")));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K1", new BigDecimal("50000.00"), SupplyNature.DOMESTIC_PACKAGE, null, false, "INR"));

        assertThat(result.taxTreatment()).isEqualTo(TaxTreatment.INTRA_STATE);
        assertThat(result.cgstRatePercent()).isEqualByComparingTo("2.500");
        assertThat(result.sgstRatePercent()).isEqualByComparingTo("2.500");
        assertThat(result.igstRatePercent()).isEqualByComparingTo("0");
        assertThat(result.cgstAmount()).isEqualByComparingTo("1250.00");
        assertThat(result.sgstAmount()).isEqualByComparingTo("1250.00");
        assertThat(result.igstAmount()).isEqualByComparingTo("0.00");
        assertThat(result.gstTotal()).isEqualByComparingTo("2500.00");
        assertThat(result.grandTotal()).isEqualByComparingTo("52500.00");
    }

    @Test
    void differentStateClientChargesIgstOnly() {
        when(clientService.findAccessibleClient("K2")).thenReturn(
                Client.builder().id("K2").name("Raj").stateCode("09").isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.AIR_TICKET))
                .thenReturn(Optional.of(gstConfig(SupplyNature.AIR_TICKET, "18.000")));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K2", new BigDecimal("10000.00"), SupplyNature.AIR_TICKET, null, false, "INR"));

        assertThat(result.taxTreatment()).isEqualTo(TaxTreatment.INTER_STATE);
        assertThat(result.cgstAmount()).isEqualByComparingTo("0.00");
        assertThat(result.sgstAmount()).isEqualByComparingTo("0.00");
        assertThat(result.igstRatePercent()).isEqualByComparingTo("18.000");
        assertThat(result.igstAmount()).isEqualByComparingTo("1800.00");
        assertThat(result.gstTotal()).isEqualByComparingTo("1800.00");
    }

    @Test
    void placeOfSupplyOverrideWinsOverClientState() {
        when(clientService.findAccessibleClient("K3")).thenReturn(
                Client.builder().id("K3").name("Amit").stateCode("09").isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.HOTEL_ONLY))
                .thenReturn(Optional.of(gstConfig(SupplyNature.HOTEL_ONLY, "18.000")));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K3", new BigDecimal("1000.00"), SupplyNature.HOTEL_ONLY, "27", false, "INR"));

        assertThat(result.placeOfSupplyCode()).isEqualTo("27");
        assertThat(result.taxTreatment()).isEqualTo(TaxTreatment.INTRA_STATE);
    }

    @Test
    void unregisteredClientWithNoStateFallsBackToAgencyState() {
        when(clientService.findAccessibleClient("K4")).thenReturn(
                Client.builder().id("K4").name("Walk-in").stateCode(null).isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.OTHER))
                .thenReturn(Optional.of(gstConfig(SupplyNature.OTHER, "18.000")));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K4", new BigDecimal("1000.00"), SupplyNature.OTHER, null, false, "INR"));

        assertThat(result.placeOfSupplyCode()).isEqualTo("27");
        assertThat(result.taxTreatment()).isEqualTo(TaxTreatment.INTRA_STATE);
    }

    @Test
    void exportOfServiceRequiresOverseasFlagAndNonInrCurrencyAndExplicitRequest() {
        when(clientService.findAccessibleClient("K5")).thenReturn(
                Client.builder().id("K5").name("Overseas Co").stateCode(null).isOverseas(true).build());

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K5", new BigDecimal("1000.00"), SupplyNature.OTHER, null, true, "USD"));

        assertThat(result.taxTreatment()).isEqualTo(TaxTreatment.EXPORT_OF_SERVICE);
        assertThat(result.gstTotal()).isEqualByComparingTo("0.00");
        assertThat(result.grandTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void overseasClientInInrIsNotTreatedAsExportEvenIfRequested() {
        when(clientService.findAccessibleClient("K6")).thenReturn(
                Client.builder().id("K6").name("Overseas Co").stateCode(null).isOverseas(true).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.OTHER))
                .thenReturn(Optional.of(gstConfig(SupplyNature.OTHER, "18.000")));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K6", new BigDecimal("1000.00"), SupplyNature.OTHER, null, true, "INR"));

        assertThat(result.taxTreatment()).isNotEqualTo(TaxTreatment.EXPORT_OF_SERVICE);
    }

    @Test
    void overseasPackageAddsTcsOnTopOfGst() {
        when(clientService.findAccessibleClient("K7")).thenReturn(
                Client.builder().id("K7").name("Traveller").stateCode("27").isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.OVERSEAS_PACKAGE))
                .thenReturn(Optional.of(gstConfig(SupplyNature.OVERSEAS_PACKAGE, "5.000")));
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.TCS, SupplyNature.OVERSEAS_PACKAGE))
                .thenReturn(Optional.of(TaxRateConfig.builder()
                        .id("TCS1").taxKind(TaxKind.TCS).label("Overseas TCS")
                        .supplyNature(SupplyNature.OVERSEAS_PACKAGE).ratePercent(new BigDecimal("5.000"))
                        .thresholdAmount(new BigDecimal("700000.00")).tcsSection("206C(1G)")
                        .effectiveFrom(LocalDate.now()).isDefault(true).isActive(true)
                        .build()));

        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                "K7", new BigDecimal("100000.00"), SupplyNature.OVERSEAS_PACKAGE, null, false, "INR"));

        assertThat(result.gstTotal()).isEqualByComparingTo("5000.00");
        assertThat(result.tcsRatePercent()).isEqualByComparingTo("5.000");
        assertThat(result.tcsSection()).isEqualTo("206C(1G)");
        assertThat(result.tcsAmount()).isEqualByComparingTo("5000.00");
        assertThat(result.grandTotal()).isEqualByComparingTo("110000.00");
    }

    @Test
    void missingDefaultGstConfigThrowsAClearError() {
        when(clientService.findAccessibleClient("K8")).thenReturn(
                Client.builder().id("K8").name("Jane").stateCode("27").isOverseas(false).build());
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, SupplyNature.INSURANCE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxEngine.compute(new TaxComputationRequest(
                "K8", new BigDecimal("1000.00"), SupplyNature.INSURANCE, null, false, "INR")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default GST rate configured");
    }
}
