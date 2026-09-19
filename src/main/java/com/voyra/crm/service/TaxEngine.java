package com.voyra.crm.service;

import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.repository.TaxRateConfigRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes GST and TCS for one taxable amount. Reads {@code tax_rate_config} and the current
 * client/agency; writes nothing. Every figure it returns is exactly what an invoice line
 * would freeze at issue (see {@link com.voyra.crm.models.TaxComputationResult}), so a caller
 * can persist the result verbatim rather than re-deriving it later - that persistence is what
 * lets a historical invoice be reproduced years after a rate has changed.
 */
@Service
@RequiredArgsConstructor
public class TaxEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final ClientService clientService;
    private final TenantRepository tenantRepository;
    private final TaxRateConfigRepository taxRateConfigRepository;

    @Transactional(readOnly = true)
    public TaxComputationResult compute(TaxComputationRequest request) {
        Client client = clientService.findAccessibleClient(request.clientId());
        Tenant agency = currentAgency();

        TaxTreatment treatment = determineTreatment(request, client, agency);
        String placeOfSupplyCode = resolvePlaceOfSupply(request, client, agency);

        if (treatment == TaxTreatment.EXPORT_OF_SERVICE || treatment == TaxTreatment.EXEMPT) {
            return zeroGst(treatment, placeOfSupplyCode, request);
        }

        TaxRateConfig gstConfig = taxRateConfigRepository
                .findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, request.supplyNature())
                .orElseThrow(() -> new IllegalStateException(
                        "No default GST rate configured for supply nature " + request.supplyNature()
                                + " - ask the Agency Owner to add one under Tax Settings"));

        BigDecimal taxableValue = pct(request.taxableAmount(), gstConfig.getTaxablePercent());
        BigDecimal rate = gstConfig.getRatePercent();

        BigDecimal cgstRate = BigDecimal.ZERO;
        BigDecimal sgstRate = BigDecimal.ZERO;
        BigDecimal igstRate = BigDecimal.ZERO;
        if (treatment == TaxTreatment.INTRA_STATE) {
            cgstRate = rate.divide(TWO, 3, RoundingMode.HALF_UP);
            sgstRate = cgstRate;
        } else {
            igstRate = rate;
        }

        BigDecimal cgstAmount = pct(taxableValue, cgstRate);
        BigDecimal sgstAmount = pct(taxableValue, sgstRate);
        BigDecimal igstAmount = pct(taxableValue, igstRate);
        BigDecimal gstTotal = cgstAmount.add(sgstAmount).add(igstAmount);

        BigDecimal tcsRate = BigDecimal.ZERO;
        BigDecimal tcsBase = BigDecimal.ZERO;
        BigDecimal tcsAmount = BigDecimal.ZERO;
        String tcsSection = null;
        if (request.supplyNature() == SupplyNature.OVERSEAS_PACKAGE) {
            TaxRateConfig tcsConfig = taxRateConfigRepository
                    .findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.TCS, SupplyNature.OVERSEAS_PACKAGE)
                    .orElse(null);
            if (tcsConfig != null) {
                tcsRate = tcsConfig.getRatePercent();
                // Per-client, per-financial-year threshold accumulation needs invoice history,
                // which does not exist until Epic 3 - applied flat here, threshold enforcement
                // lands with InvoiceRepository.sumOverseasConsiderationForClientInFy.
                tcsBase = request.taxableAmount();
                tcsAmount = pct(tcsBase, tcsRate);
                tcsSection = tcsConfig.getTcsSection();
            }
        }

        BigDecimal grandTotal = request.taxableAmount().add(gstTotal).add(tcsAmount);

        return new TaxComputationResult(
                treatment, placeOfSupplyCode, taxableValue,
                rate, cgstRate, sgstRate, igstRate,
                cgstAmount, sgstAmount, igstAmount, gstTotal,
                tcsRate, tcsSection, tcsBase, tcsAmount,
                grandTotal);
    }

    /**
     * EXPORT_OF_SERVICE only when the accountant explicitly requests it AND the client is
     * marked overseas AND the invoice currency is non-INR. Deliberately narrow: for a tour
     * operator with an India-established recipient, the place of supply is in India
     * regardless of destination, so destination must never auto-trigger export.
     */
    private TaxTreatment determineTreatment(TaxComputationRequest request, Client client, Tenant agency) {
        boolean nonInr = request.currencyCode() != null && !"INR".equalsIgnoreCase(request.currencyCode());
        if (request.exportOfServiceRequested() && Boolean.TRUE.equals(client.getIsOverseas()) && nonInr) {
            return TaxTreatment.EXPORT_OF_SERVICE;
        }

        String placeOfSupplyCode = resolvePlaceOfSupply(request, client, agency);
        String agencyState = agency.getStateCode();
        if (agencyState == null || placeOfSupplyCode == null) {
            throw new IllegalStateException(
                    "Both the agency's GST state and a place of supply are required to compute GST - "
                            + "set the agency's state under Settings, and the client's state or an override here");
        }
        return placeOfSupplyCode.equals(agencyState) ? TaxTreatment.INTRA_STATE : TaxTreatment.INTER_STATE;
    }

    /**
     * Override, then the client's own state, then the agency's own state (location of
     * supplier, the correct fallback for a B2C recipient with no state on file). The
     * accountant's override is what a real invoice persists - determination never re-runs
     * after issue.
     */
    private String resolvePlaceOfSupply(TaxComputationRequest request, Client client, Tenant agency) {
        if (request.placeOfSupplyCodeOverride() != null && !request.placeOfSupplyCodeOverride().isBlank()) {
            return request.placeOfSupplyCodeOverride();
        }
        if (client.getStateCode() != null && !client.getStateCode().isBlank()) {
            return client.getStateCode();
        }
        return agency.getStateCode();
    }

    private TaxComputationResult zeroGst(TaxTreatment treatment, String placeOfSupplyCode, TaxComputationRequest request) {
        return new TaxComputationResult(
                treatment, placeOfSupplyCode, request.taxableAmount(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO,
                request.taxableAmount());
    }

    private Tenant currentAgency() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + tenantId));
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal ratePercent) {
        return base.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
